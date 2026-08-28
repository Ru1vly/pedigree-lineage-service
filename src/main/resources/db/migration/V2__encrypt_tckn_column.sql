-- Flyway Migration V2: Field-Level Encryption at Rest schema update
-- Alters national_id columns to VARCHAR(512) to accommodate HashiCorp Vault Transit Engine & AES-256-GCM ciphertexts.
-- Ensures DBAs querying PostgreSQL read only encrypted payloads, safeguarding citizen identity details.

ALTER TABLE lineage_queries ALTER COLUMN national_id TYPE VARCHAR(512);
ALTER TABLE lineage_audit_logs ALTER COLUMN national_id TYPE VARCHAR(512);
