-- Membership Phase 2: cards and the immutable balance ledger. Money is integer paise.

CREATE TABLE membership_cards (
    id                       VARCHAR(36)  NOT NULL,
    cafe_id                  VARCHAR(36)  NOT NULL,
    card_number              VARCHAR(30)  NOT NULL,
    customer_name            VARCHAR(255) NOT NULL,
    customer_phone           VARCHAR(20)  NOT NULL,

    -- Snapshot of the plan sold for the current validity period (never read back from membership_plans)
    plan_id                  VARCHAR(36)  NOT NULL,
    plan_name                VARCHAR(60)  NOT NULL,
    plan_color               VARCHAR(20),
    plan_badge               VARCHAR(40),
    plan_load_amount_paise   BIGINT       NOT NULL,
    plan_bonus_type          VARCHAR(10)  NOT NULL,
    plan_bonus_value         BIGINT       NOT NULL,
    plan_bonus_amount_paise  BIGINT       NOT NULL,
    plan_validity_days       INT          NOT NULL,

    -- Cached balances; always equal to the ledger sums (see membership_ledger)
    paid_balance_paise       BIGINT       NOT NULL DEFAULT 0,
    bonus_balance_paise      BIGINT       NOT NULL DEFAULT 0,

    -- ACTIVE | EXPIRED | SUSPENDED | CLOSED  (SCHEDULED / EXPIRED are also derived from dates on read)
    status                   VARCHAR(20)  NOT NULL,
    start_date               DATE         NOT NULL,
    -- Last day the card can be used (inclusive)
    expiry_date              DATE         NOT NULL,

    issued_at                DATETIME(6)  NOT NULL,
    period_purchased_at      DATETIME(6)  NOT NULL,
    last_used_at             DATETIME(6),
    issued_by_user_id        VARCHAR(36),
    issued_by_name           VARCHAR(255),

    version                  BIGINT       NOT NULL DEFAULT 0,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_membership_cards_number UNIQUE (cafe_id, card_number)
);

CREATE INDEX idx_membership_cards_phone  ON membership_cards (cafe_id, customer_phone);
CREATE INDEX idx_membership_cards_status ON membership_cards (cafe_id, status, expiry_date);

CREATE TABLE membership_ledger (
    id                         BIGINT       NOT NULL AUTO_INCREMENT,
    cafe_id                    VARCHAR(36)  NOT NULL,
    card_id                    VARCHAR(36)  NOT NULL,
    -- LOAD | RECHARGE | BONUS | BILL_DEBIT | REVERSAL | ADJUSTMENT | EXPIRY_FORFEIT | RECHARGE_FORFEIT
    -- | CLOSE_FORFEIT | EXPIRY_EXTENDED | SUSPENDED | REACTIVATED | CLOSED
    entry_type                 VARCHAR(30)  NOT NULL,
    -- Signed: credits positive, debits negative. amount = paid_portion + bonus_portion.
    amount_paise               BIGINT       NOT NULL,
    paid_portion_paise         BIGINT       NOT NULL,
    bonus_portion_paise        BIGINT       NOT NULL,
    paid_balance_after_paise   BIGINT       NOT NULL,
    bonus_balance_after_paise  BIGINT       NOT NULL,
    payment_mode               VARCHAR(20),
    plan_id                    VARCHAR(36),
    plan_name                  VARCHAR(60),
    session_id                 VARCHAR(36),
    reverses_entry_id          BIGINT,
    -- Unique when present: makes retries / double-clicks safe
    idempotency_key            VARCHAR(150),
    user_id                    VARCHAR(36),
    user_name                  VARCHAR(255),
    note                       VARCHAR(500),
    created_at                 DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_membership_ledger_idem UNIQUE (idempotency_key)
);

CREATE INDEX idx_membership_ledger_card    ON membership_ledger (card_id, id);
CREATE INDEX idx_membership_ledger_type    ON membership_ledger (cafe_id, entry_type, created_at);
CREATE INDEX idx_membership_ledger_session ON membership_ledger (session_id);

-- Per-cafe counter for human-friendly card numbers (MC-000001, ...)
ALTER TABLE membership_settings ADD COLUMN next_card_seq BIGINT NOT NULL DEFAULT 1;
