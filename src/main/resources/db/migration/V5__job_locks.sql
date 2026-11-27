-- Simple database lock for background jobs, so two runs (double trigger, restart overlap, several app servers)
-- never execute at the same time. A run claims the row with a conditional UPDATE and an expiry time, so a
-- crashed run cannot block the job forever.
CREATE TABLE job_locks (
    name          VARCHAR(64)  NOT NULL,
    locked_until  DATETIME(6),
    locked_by     VARCHAR(100),
    locked_at     DATETIME(6),
    PRIMARY KEY (name)
);

INSERT INTO job_locks (name, locked_until, locked_by, locked_at) VALUES ('audit_purge', NULL, NULL, NULL);
