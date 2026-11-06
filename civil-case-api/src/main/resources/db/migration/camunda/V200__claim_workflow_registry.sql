-- A durable business-key guard: engine history has a shorter retention period than claims.
CREATE TABLE civil.claim_workflows (
    reference VARCHAR(30) PRIMARY KEY,
    process_instance_id VARCHAR(64),
    response_received BOOLEAN NOT NULL DEFAULT FALSE
);
