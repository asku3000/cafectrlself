-- Membership Phase 1: admin-configurable plans and per-cafe membership settings.
-- All money columns are integer paise (BIGINT). No foreign keys to pre-existing tables,
-- so this script runs the same on a fresh database and on an existing one.

CREATE TABLE membership_plans (
    id                VARCHAR(36)   NOT NULL,
    cafe_id           VARCHAR(36)   NOT NULL,
    name              VARCHAR(60)   NOT NULL,
    load_amount_paise BIGINT        NOT NULL,
    -- FIXED: bonus_value is paise. PERCENT: bonus_value is basis points (1000 = 10%).
    bonus_type        VARCHAR(10)   NOT NULL,
    bonus_value       BIGINT        NOT NULL DEFAULT 0,
    validity_days     INT           NOT NULL,
    is_active         BOOLEAN       NOT NULL DEFAULT TRUE,
    color             VARCHAR(20),
    badge             VARCHAR(40),
    sort_order        INT           NOT NULL DEFAULT 0,
    description       VARCHAR(1000),
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    updated_by_name   VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE INDEX idx_membership_plans_cafe ON membership_plans (cafe_id, sort_order);

CREATE TABLE membership_settings (
    cafe_id                     VARCHAR(36)  NOT NULL,
    -- PAID_FIRST | BONUS_FIRST
    deduction_order             VARCHAR(20)  NOT NULL DEFAULT 'PAID_FIRST',
    low_balance_threshold_paise BIGINT       NOT NULL DEFAULT 10000,
    card_number_prefix          VARCHAR(10)  NOT NULL DEFAULT 'MC',
    updated_at                  DATETIME(6),
    updated_by_name             VARCHAR(255),
    PRIMARY KEY (cafe_id)
);
