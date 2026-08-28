-- Flyway Initial Database Migration Schema
-- Pedigree / Family Tree Lineage Async Query Processing System

CREATE TABLE lineage_queries (
    id UUID PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    national_id VARCHAR(512) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    user_roles VARCHAR(256),
    status VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED',
    current_phase VARCHAR(64) DEFAULT 'INITIATED',
    progress_percentage INT NOT NULL DEFAULT 0,
    generations_depth INT NOT NULL DEFAULT 3,
    include_certificates BOOLEAN NOT NULL DEFAULT FALSE,
    document_format VARCHAR(16) NOT NULL DEFAULT 'PDF',
    request_payload TEXT NOT NULL,
    result_payload TEXT,
    result_download_url VARCHAR(512),
    error_code VARCHAR(64),
    error_message VARCHAR(1024),
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3,
    trace_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_lineage_queries_tx_id ON lineage_queries(transaction_id);
CREATE INDEX idx_lineage_queries_user_id ON lineage_queries(user_id);
CREATE INDEX idx_lineage_queries_idempotency ON lineage_queries(idempotency_key);
CREATE INDEX idx_lineage_queries_status ON lineage_queries(status);
CREATE INDEX idx_lineage_queries_created_at ON lineage_queries(created_at DESC);

-- Transactional Outbox Table for reliable messaging
CREATE TABLE transactional_outbox (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_outbox_status_created ON transactional_outbox(status, created_at);

-- Security Audit Log Table
CREATE TABLE lineage_audit_logs (
    id UUID PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    national_id VARCHAR(512) NOT NULL,
    action VARCHAR(64) NOT NULL,
    ip_address VARCHAR(45),
    user_agent VARCHAR(256),
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    details TEXT
);

CREATE INDEX idx_audit_tx_user ON lineage_audit_logs(transaction_id, user_id);
