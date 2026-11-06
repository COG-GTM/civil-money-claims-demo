CREATE SEQUENCE civil.claim_reference_seq START WITH 161;
CREATE TABLE civil.claims (
    id UUID PRIMARY KEY,
    reference VARCHAR(30) NOT NULL UNIQUE,
    claimant_id VARCHAR(100) NOT NULL DEFAULT 'demo-claimant',
    state VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
    claimant JSONB NOT NULL DEFAULT '{}',
    defendant JSONB NOT NULL DEFAULT '{}',
    amount_breakdown JSONB NOT NULL DEFAULT '[]',
    principal_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    interest JSONB NOT NULL DEFAULT '{"claimed":false,"rate":8}',
    interest_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    daily_interest NUMERIC(14,6) NOT NULL DEFAULT 0,
    fee NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    reason TEXT NOT NULL DEFAULT '',
    timeline JSONB NOT NULL DEFAULT '[]',
    security_code VARCHAR(8) NOT NULL,
    acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    issued_at TIMESTAMPTZ,
    service_date DATE,
    response_deadline DATE,
    response JSONB,
    judgment_requested_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT claim_state CHECK (state IN ('DRAFT','ISSUED','AWAITING_RESPONSE','RESPONDED','AWAITING_DEFAULT_JUDGMENT','SETTLED','TRANSFERRED'))
);
CREATE INDEX claims_state_date_idx ON civil.claims(state, created_at);
CREATE INDEX claims_claimant_idx ON civil.claims(claimant_id);
CREATE TABLE civil.claim_events (
    id UUID PRIMARY KEY, claim_id UUID NOT NULL REFERENCES civil.claims(id) ON DELETE CASCADE,
    type VARCHAR(80) NOT NULL, description TEXT NOT NULL, actor VARCHAR(100) NOT NULL DEFAULT 'system',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE civil.case_notes (
    id UUID PRIMARY KEY, claim_id UUID NOT NULL REFERENCES civil.claims(id) ON DELETE CASCADE,
    text TEXT NOT NULL, author VARCHAR(100) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE civil.documents (
    id UUID PRIMARY KEY, claim_id UUID NOT NULL REFERENCES civil.claims(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL, content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL, content BYTEA NOT NULL,
    scan_status VARCHAR(20) NOT NULL DEFAULT 'CLEAN', created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE civil.bank_holidays (date DATE PRIMARY KEY, title VARCHAR(100) NOT NULL);
