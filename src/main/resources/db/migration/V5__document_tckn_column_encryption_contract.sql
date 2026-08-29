-- Flyway Migration V5: record what the national_id columns actually contain.
--
-- V2 is named "encrypt_tckn_column" and encrypts nothing. It widens national_id to VARCHAR(512)
-- so a ciphertext fits - which is a necessary step and not the one its name claims. There is no
-- backfill in it, and there cannot be one written in SQL: the key lives in the application (or in
-- Vault), never in the database, which is the entire point of encrypting the field there. So rows
-- written before field-level encryption existed are still stored in the clear, and a DBA reading
-- this table sees a mixture with nothing to explain it.
--
-- Renaming V2 is not an option - Flyway checksums applied migrations, and editing one breaks
-- validation on every database that has already run it. Stating the contract where the person
-- looking at the column will see it is.
--
-- The application discriminates on the prefix (see VaultTransitTcknEncryptionService):
--   vault:v1:<...>            HashiCorp Vault Transit ciphertext; Vault holds the key.
--   enc:v2:<keyId>:<base64>   AES-256-GCM under the named key from the application keyring.
--   enc:v1:gcm:<base64>       Legacy AES-256-GCM, read-only; rewritten as enc:v2 on next save.
--   11 digits                 Pre-encryption plaintext. Read with a WARN, rewritten on next save.
--   anything else             Rejected. Not guessed at, not passed through.
--
-- Migrating the remaining plaintext rows is therefore an application-side rewrite (load and save
-- each affected row through JPA so the attribute converter encrypts it), not a SQL statement.

COMMENT ON COLUMN lineage_queries.national_id IS
    'Encrypted at rest by the application. Prefix names the format: vault:v1: (Vault Transit), '
    'enc:v2:<keyId>: (AES-256-GCM, current), enc:v1:gcm: (legacy, read-only). A bare 11-digit '
    'value is a pre-encryption row awaiting rewrite. Never write this column directly.';

COMMENT ON COLUMN lineage_audit_logs.national_id IS
    'Encrypted at rest by the application - same formats as lineage_queries.national_id. The admin '
    'audit API never returns this value unmasked.';
