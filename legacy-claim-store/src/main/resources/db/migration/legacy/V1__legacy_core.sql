CREATE SEQUENCE legacy_cmc.claim_reference_seq START WITH 41;
CREATE TABLE legacy_cmc.claims (
    id UUID PRIMARY KEY, reference VARCHAR(30) NOT NULL UNIQUE,
    claimant_id VARCHAR(100) NOT NULL DEFAULT 'demo-claimant',
    state VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
    claim_data TEXT NOT NULL,
    security_code VARCHAR(8) NOT NULL,
    service_date DATE, response_deadline DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX legacy_claims_state_date_idx ON legacy_cmc.claims(state, created_at);
CREATE TABLE legacy_cmc.offers (
    id UUID PRIMARY KEY, claim_id UUID NOT NULL REFERENCES legacy_cmc.claims(id) ON DELETE CASCADE,
    amount NUMERIC(14,2) NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    offer_data TEXT NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE legacy_cmc.claim_events (
    id UUID PRIMARY KEY, claim_id UUID NOT NULL REFERENCES legacy_cmc.claims(id) ON DELETE CASCADE,
    type VARCHAR(80) NOT NULL, description TEXT NOT NULL, actor VARCHAR(100) NOT NULL DEFAULT 'system',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE legacy_cmc.case_notes (
    id UUID PRIMARY KEY, claim_id UUID NOT NULL REFERENCES legacy_cmc.claims(id) ON DELETE CASCADE,
    text TEXT NOT NULL, author VARCHAR(100) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE legacy_cmc.documents (
    id UUID PRIMARY KEY, claim_id UUID NOT NULL REFERENCES legacy_cmc.claims(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL, content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL, content BYTEA NOT NULL,
    scan_status VARCHAR(20) NOT NULL DEFAULT 'CLEAN', created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE legacy_cmc.bank_holidays (date DATE PRIMARY KEY, title VARCHAR(100) NOT NULL);
