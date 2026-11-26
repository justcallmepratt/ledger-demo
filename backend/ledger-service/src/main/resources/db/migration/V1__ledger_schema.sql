-- ledger-core schema v1. TigerBeetle-style model on PostgreSQL.
--
-- Money is BIGINT in minor units with CHECK (>= 0). The ledger is append-only:
-- `transfers` is strictly insert-only (trigger below). Everything that changes lives
-- elsewhere: account balance counters (`accounts`), the lifecycle of a pending transfer
-- (`pending_transfers`) and outbox publish markers (`outbox`). Choosing a separate
-- `pending_transfers` table keeps the immutability trigger absolute, with no
-- "except these columns" carve-out to get wrong.

CREATE TABLE ledgers (
    id          INTEGER  PRIMARY KEY CHECK (id > 0),          -- ISO 4217 numeric code
    currency    CHAR(3)  NOT NULL,
    asset_scale SMALLINT NOT NULL CHECK (asset_scale BETWEEN 0 AND 18)
);

INSERT INTO ledgers (id, currency, asset_scale) VALUES (840, 'USD', 2);

CREATE TABLE accounts (
    id                              UUID         PRIMARY KEY,
    ledger                          INTEGER      NOT NULL REFERENCES ledgers (id),
    code                            INTEGER      NOT NULL CHECK (code BETWEEN 1 AND 65535),
    name                            VARCHAR(100) NOT NULL CHECK (char_length(name) >= 1),
    debits_must_not_exceed_credits  BOOLEAN      NOT NULL DEFAULT FALSE,
    credits_must_not_exceed_debits  BOOLEAN      NOT NULL DEFAULT FALSE,
    history                         BOOLEAN      NOT NULL DEFAULT FALSE,
    closed                          BOOLEAN      NOT NULL DEFAULT FALSE,
    debits_pending                  BIGINT       NOT NULL DEFAULT 0 CHECK (debits_pending >= 0),
    debits_posted                   BIGINT       NOT NULL DEFAULT 0 CHECK (debits_posted >= 0),
    credits_pending                 BIGINT       NOT NULL DEFAULT 0 CHECK (credits_pending >= 0),
    credits_posted                  BIGINT       NOT NULL DEFAULT 0 CHECK (credits_posted >= 0),
    created_at                      TIMESTAMPTZ  NOT NULL
);

-- GET /accounts: keyset pagination, newest first, by (created_at, id)
CREATE INDEX accounts_created_idx ON accounts (created_at DESC, id DESC);

CREATE TABLE transfers (
    seq               BIGSERIAL    NOT NULL UNIQUE,            -- gap-tolerant, increasing order key
    id                UUID         PRIMARY KEY,                -- client-supplied idempotency key
    debit_account_id  UUID         NOT NULL REFERENCES accounts (id),
    credit_account_id UUID         NOT NULL REFERENCES accounts (id),
    -- always > 0: post/void transfers store the resolved (inherited) amount
    amount            BIGINT       NOT NULL CHECK (amount > 0),
    ledger            INTEGER      NOT NULL REFERENCES ledgers (id),
    code              INTEGER      NOT NULL CHECK (code BETWEEN 1 AND 65535),
    linked            BOOLEAN      NOT NULL DEFAULT FALSE,
    pending           BOOLEAN      NOT NULL DEFAULT FALSE,
    post_pending      BOOLEAN      NOT NULL DEFAULT FALSE,
    void_pending      BOOLEAN      NOT NULL DEFAULT FALSE,
    pending_id        UUID         REFERENCES transfers (id),
    timeout_seconds   INTEGER      NOT NULL DEFAULT 0 CHECK (timeout_seconds >= 0),
    expires_at        TIMESTAMPTZ,
    reference         VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT transfers_accounts_differ CHECK (debit_account_id <> credit_account_id),
    CONSTRAINT transfers_one_phase_flag CHECK ((pending::int + post_pending::int + void_pending::int) <= 1),
    CONSTRAINT transfers_pending_id_iff_resolving
        CHECK ((post_pending OR void_pending) = (pending_id IS NOT NULL)),
    CONSTRAINT transfers_timeout_only_pending CHECK (timeout_seconds = 0 OR pending),
    CONSTRAINT transfers_expiry_only_pending CHECK (expires_at IS NULL OR pending)
);

CREATE INDEX transfers_debit_seq_idx  ON transfers (debit_account_id,  seq DESC);   -- account transfers
CREATE INDEX transfers_credit_seq_idx ON transfers (credit_account_id, seq DESC);   -- account transfers
CREATE INDEX transfers_pending_id_idx ON transfers (pending_id) WHERE pending_id IS NOT NULL;

CREATE FUNCTION forbid_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: the ledger is append-only, post a correcting transfer instead',
        TG_OP, TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER transfers_immutable
    BEFORE UPDATE OR DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER transfers_no_truncate
    BEFORE TRUNCATE ON transfers
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- Lifecycle of pending transfers. The only mutable transfer-related state.
CREATE TABLE pending_transfers (
    transfer_id UUID        PRIMARY KEY REFERENCES transfers (id),
    seq         BIGINT      NOT NULL,                         -- copy of transfers.seq for ordered listing
    status      VARCHAR(8)  NOT NULL CHECK (status IN ('PENDING', 'POSTED', 'VOIDED', 'EXPIRED')),
    expires_at  TIMESTAMPTZ,
    resolved_by UUID        REFERENCES transfers (id),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT pending_resolved_consistent CHECK ((status = 'PENDING') = (resolved_at IS NULL))
);

-- GET /transfers?pendingStatus=...: newest first within a status
CREATE INDEX pending_transfers_status_seq_idx ON pending_transfers (status, seq DESC);
-- PendingExpiryJob: open pending transfers that are due
CREATE INDEX pending_transfers_due_idx ON pending_transfers (expires_at)
    WHERE status = 'PENDING' AND expires_at IS NOT NULL;

-- Balance after each transfer, only for accounts with the `history` flag.
CREATE TABLE account_balance_history (
    id              BIGSERIAL   PRIMARY KEY,
    account_id      UUID        NOT NULL REFERENCES accounts (id),
    transfer_id     UUID        NOT NULL REFERENCES transfers (id),
    seq             BIGINT      NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL,
    debits_pending  BIGINT      NOT NULL CHECK (debits_pending >= 0),
    debits_posted   BIGINT      NOT NULL CHECK (debits_posted >= 0),
    credits_pending BIGINT      NOT NULL CHECK (credits_pending >= 0),
    credits_posted  BIGINT      NOT NULL CHECK (credits_posted >= 0)
);

-- GET /accounts/{id}/balances: keyset by id, newest first
CREATE INDEX balance_history_account_idx ON account_balance_history (account_id, id DESC);

CREATE TRIGGER balance_history_immutable
    BEFORE UPDATE OR DELETE ON account_balance_history
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- Transactional outbox: written in the same transaction as the transfer.
CREATE TABLE outbox (
    id           BIGSERIAL    PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    topic        VARCHAR(100) NOT NULL,
    message_key  VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ,
    attempts     INTEGER      NOT NULL DEFAULT 0
);

-- OutboxPublisher: oldest unpublished first
CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;

-- Bounded read model fed by Kafka (ActivityProjector); `id` gives replay order.
CREATE TABLE activity (
    id          BIGSERIAL    PRIMARY KEY,
    event_id    UUID         NOT NULL UNIQUE,                 -- dedupe key (CloudEvent id)
    type        VARCHAR(40)  NOT NULL,
    seq         BIGINT,
    transfer_id UUID,
    account_id  UUID,
    amount      BIGINT       CHECK (amount IS NULL OR amount >= 0),
    ledger      INTEGER      NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL,
    summary     VARCHAR(200) NOT NULL
);
