ALTER TABLE civil.documents ADD COLUMN description VARCHAR(2000);
ALTER TABLE civil.documents ADD COLUMN document_kind VARCHAR(30) NOT NULL DEFAULT 'EVIDENCE';
CREATE UNIQUE INDEX one_claim_form_per_claim ON civil.documents(claim_id) WHERE document_kind = 'CLAIM_FORM';
CREATE INDEX documents_claim_created_idx ON civil.documents(claim_id, created_at);
CREATE INDEX events_claim_created_idx ON civil.claim_events(claim_id, created_at);
CREATE INDEX notes_claim_created_idx ON civil.case_notes(claim_id, created_at);
