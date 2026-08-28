-- Flyway Migration V3: Scope idempotency-key uniqueness to the submitting user.
-- idempotency_key is a client-supplied value; a *global* unique constraint let one user's
-- idempotency key collide with another user's and made LineageQueryService.submitQuery hand
-- back a stranger's transactionId/status/createdAt with no ownership check. Uniqueness now
-- applies per (user_id, idempotency_key) instead, matching how the application looks it up.

ALTER TABLE lineage_queries DROP CONSTRAINT lineage_queries_idempotency_key_key;

ALTER TABLE lineage_queries ADD CONSTRAINT uq_lineage_queries_user_idempotency
    UNIQUE (user_id, idempotency_key);
