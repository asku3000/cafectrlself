-- Activity / audit log. Append-only from the application; only the purge job deletes rows.
-- occurred_at is UTC (the UI shows Asia/Kolkata). Secrets are masked before they reach this table.
-- Replaces the old Hibernate-managed audit_logs table, which is no longer written to (its rows are left as they are).

CREATE TABLE audit_events (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    occurred_at     DATETIME(6)   NOT NULL,
    cafe_id         VARCHAR(36),
    -- USER | SYSTEM
    actor_type      VARCHAR(10)   NOT NULL,
    actor_user_id   VARCHAR(36),
    -- Snapshot at the time of the action, so the log stays readable if the user is renamed or deleted
    actor_name      VARCHAR(255),
    actor_role      VARCHAR(30),
    action          VARCHAR(60)   NOT NULL,
    entity_type     VARCHAR(40),
    entity_id       VARCHAR(64),
    description     VARCHAR(1000),
    -- Changed fields only: {"field": {"before": ..., "after": ...}}
    changes         JSON,
    -- Extra context, e.g. payment splits
    details         JSON,
    -- SUCCESS | FAILURE
    outcome         VARCHAR(10)   NOT NULL,
    reason          VARCHAR(500),
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(255),
    request_id      VARCHAR(64),
    PRIMARY KEY (id)
);

CREATE INDEX idx_audit_events_time    ON audit_events (occurred_at);
CREATE INDEX idx_audit_events_cafe    ON audit_events (cafe_id, occurred_at);
CREATE INDEX idx_audit_events_actor   ON audit_events (actor_user_id, occurred_at);
CREATE INDEX idx_audit_events_action  ON audit_events (action, occurred_at);
CREATE INDEX idx_audit_events_entity  ON audit_events (entity_type, entity_id, occurred_at);
