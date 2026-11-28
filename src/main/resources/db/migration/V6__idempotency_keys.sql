-- Idempotency keys for critical actions (billing, payments, sessions, membership cards).
-- The frontend sends one Idempotency-Key per user action. The key and the response are stored in the SAME
-- transaction as the action, so a repeated request (double click, retry, second device) returns the original
-- response instead of doing the action again. Rows expire after 24 hours and are cleaned up daily.
CREATE TABLE idempotency_keys (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    -- who may replay it: the user id (keys are random UUIDs; scoping just prevents cross-user replays)
    scope            VARCHAR(64)   NOT NULL,
    idem_key         VARCHAR(100)  NOT NULL,
    -- e.g. SESSION_CHECKOUT
    action           VARCHAR(60)   NOT NULL,
    -- SHA-256 of action + path + request body: the same key may not be reused for a different request
    request_hash     CHAR(64)      NOT NULL,
    response_status  INT,
    response_body    MEDIUMTEXT,
    created_at       DATETIME(6)   NOT NULL,
    expires_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_scope_key UNIQUE (scope, idem_key)
);

CREATE INDEX idx_idempotency_expires ON idempotency_keys (expires_at);
