-- Debezium now tails transactional_outbox via the PostgreSQL WAL (log-based CDC) instead of
-- OutboxPublisherScheduler polling it, so the publish-tracking columns that scheduler used to
-- read and update (status/retry_count/processed_at) are no longer written or read by anything.
ALTER TABLE transactional_outbox
    DROP COLUMN status,
    DROP COLUMN retry_count,
    DROP COLUMN processed_at;

DROP INDEX IF EXISTS idx_outbox_status_created;
