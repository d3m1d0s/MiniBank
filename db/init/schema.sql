-- Creates the MiniBank schema in an empty database. It drops nothing, so running it
-- against a database that already has these tables fails instead of destroying them.
-- To start over on purpose, run ../reset.sql.

------------------------------------------------------------
-- CUSTOMERS
------------------------------------------------------------

CREATE TABLE customers (
                           id      INTEGER PRIMARY KEY,
                           name    VARCHAR(255) NOT NULL,
                           email   VARCHAR(255) NOT NULL,
                           street  VARCHAR(255) NOT NULL,
                           city    VARCHAR(255) NOT NULL
);

CREATE SEQUENCE customers_id_seq;
ALTER TABLE customers
    ALTER COLUMN id SET DEFAULT nextval('customers_id_seq');

ALTER SEQUENCE customers_id_seq OWNED BY customers.id;

------------------------------------------------------------
-- USERS (authentication / roles)
------------------------------------------------------------

CREATE TABLE users (
                       id             INTEGER PRIMARY KEY,
                       username       VARCHAR(64) NOT NULL UNIQUE,
                       role           VARCHAR(32) NOT NULL, -- name of UserRole enum
                       customer_id    INTEGER NULL REFERENCES customers(id) ON DELETE SET NULL,
                       password_hash  BYTEA NOT NULL,
                       password_salt  BYTEA NOT NULL
);

CREATE SEQUENCE users_id_seq;
ALTER TABLE users
    ALTER COLUMN id SET DEFAULT nextval('users_id_seq');

ALTER SEQUENCE users_id_seq OWNED BY users.id;

------------------------------------------------------------
-- ACCOUNTS
-- domain model: Account(id, iban, balance, dailyLimit, softDailyThreshold)
-- relation to Customer: via customer_id (persistence detail)
--
-- The old comment here listed transferIds as a column of this table. It never was one:
-- the SQL backend has always discarded that list, only the JSON backend held it, and the
-- field is gone from the domain with this change. Corrected rather than deleted, because a
-- reader comparing the two backends needs to know the aggregate is the same on both.
--
-- version is not a domain field either: it is the optimistic-lock token, see
-- SqlAccountRepository.upsertAccount. The JSON backend has none and needs none.
------------------------------------------------------------

CREATE TABLE accounts (
                          id                        INTEGER PRIMARY KEY,
                          iban                      VARCHAR(34) NOT NULL UNIQUE,
                          balance_czk               NUMERIC(14,2) NOT NULL,
                          daily_limit_czk           NUMERIC(14,2) NOT NULL,
                          -- NULL means "no override": RuleBasedRiskService applies the
                          -- bank-wide soft tier. A value below daily_limit_czk is what makes
                          -- the account genuinely two-tiered; at or above it the soft tier is
                          -- inert, which is deliberately still representable - a bank may want
                          -- an account with one tier. No CHECK relates the two.
                          soft_daily_threshold_czk  NUMERIC(14,2),
                          -- Bumped by every guarded write. A writer that read version N and
                          -- finds N+1 is refused instead of overwriting.
                          version                   INTEGER NOT NULL DEFAULT 0,
                          customer_id               INTEGER REFERENCES customers(id) ON DELETE CASCADE
);

CREATE SEQUENCE accounts_id_seq;
ALTER TABLE accounts
    ALTER COLUMN id SET DEFAULT nextval('accounts_id_seq');

ALTER SEQUENCE accounts_id_seq OWNED BY accounts.id;

CREATE INDEX idx_accounts_customer_id ON accounts(customer_id);

------------------------------------------------------------
-- BENEFICIARIES
-- domain model: Beneficiary(id, name, IBAN, trusted)
-- relation to owner: customer_id
------------------------------------------------------------

CREATE TABLE beneficiaries (
                               id          INTEGER PRIMARY KEY,
                               customer_id INTEGER NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
                               name        VARCHAR(255) NOT NULL,
                               iban        VARCHAR(34) NOT NULL,
                               trusted     BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE SEQUENCE beneficiaries_id_seq;
ALTER TABLE beneficiaries
    ALTER COLUMN id SET DEFAULT nextval('beneficiaries_id_seq');

ALTER SEQUENCE beneficiaries_id_seq OWNED BY beneficiaries.id;

CREATE INDEX idx_beneficiaries_customer_id ON beneficiaries(customer_id);

------------------------------------------------------------
-- TRANSFERS
-- domain model: Transfer(
--   id,
--   sourceAccountId,
--   beneficiaryId,
--   targetIbanSnapshot,
--   Money amount,
--   Money fee,
--   String currency,
--   String message,
--   TransferStatus status,
--   Instant createdAt,
--   Instant settledAt,
--   Payment authMethod,
--   String declineReason
-- )
--
-- fee is the fee actually charged, written once by Transfer.send. NULL until the transfer
-- settles, and NULL forever on one that never did. Not recomputed on display, which is the
-- whole point of A14: swapping the FeePolicy bean must not silently restate what a customer
-- was charged last month.
--
-- settled_at is when the money moved; created_at is when the order was placed. The daily
-- total is keyed on settled_at, falling back to created_at for rows written before this
-- column existed - see SqlTransferRepository.sumSentWithConnection.
------------------------------------------------------------

CREATE TABLE transfers (
                           id                   INTEGER PRIMARY KEY,
                           source_account_id    INTEGER NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
                           beneficiary_id       INTEGER REFERENCES beneficiaries(id) ON DELETE SET NULL,
                           target_iban_snapshot VARCHAR(34) NOT NULL,

                           amount               NUMERIC(14,2) NOT NULL,
                           currency             VARCHAR(3) NOT NULL,
                           fee                  NUMERIC(14,2),
                           -- The customer's own reference. 140 characters is the SEPA
                           -- remittance-information length; the service refuses anything longer.
                           message              VARCHAR(140),

                           status               VARCHAR(32) NOT NULL,
                           created_at           TIMESTAMPTZ,
                           settled_at           TIMESTAMPTZ,

                           auth_method          VARCHAR(32),
                           card_number_masked   VARCHAR(64),
                           decline_reason       TEXT,
                           auth_attempts        INTEGER,
                           auth_valid_until     TIMESTAMPTZ,

                           -- Bumped by every guarded write, exactly as accounts.version is.
                           -- A6 gave the column to accounts alone because the measured leak was
                           -- on the balance; this row needs its own, because cancelPayment and
                           -- the two authorization failure paths write the transfer and never
                           -- touch accounts, so accounts.version cannot see them.
                           version              INTEGER NOT NULL DEFAULT 0,

                           -- The same invariant Transfer's constructor enforces, now also
                           -- enforced against anything that writes this table without going
                           -- through the domain: psql, a future report job, a bad migration.
                           -- Named explicitly so a migrated database and a fresh one carry the
                           -- same constraint name.
                           CONSTRAINT transfers_amount_positive CHECK (amount > 0)
);

CREATE SEQUENCE transfers_id_seq;
ALTER TABLE transfers
    ALTER COLUMN id SET DEFAULT nextval('transfers_id_seq');

ALTER SEQUENCE transfers_id_seq OWNED BY transfers.id;

-- Serves the daily-total query, in both its whole-account and its per-payee form. Leads with
-- source_account_id, so it also covers every plain lookup of one account's transfers; the
-- single-column index that used to do that alone is subsumed. currency is left out on purpose:
-- every row is CZK, so it would widen each entry and let the planner skip nothing.
CREATE INDEX idx_transfers_daily_total
    ON transfers (source_account_id, status, (COALESCE(settled_at, created_at)));

------------------------------------------------------------
-- FRAUD ALERTS
-- domain model: FraudAlert(id, transferId, FraudAlertState, ...)
-- plus the analyst's verdict, who made it and when.
--
-- decision / decided_by / resolved_at are written together by FraudAlert.approve and
-- FraudAlert.markSuspicious. decision and resolved_at have been declared here since the
-- table was created and were written by nothing and read by nothing; B1's remaining half is
-- wiring them up, which is why decided_by joins them rather than arriving alone.
-- decided_by is NULL for a decision made from the console, which has no login.
------------------------------------------------------------

CREATE TABLE fraud_alerts (
                              id          INTEGER PRIMARY KEY,
                              transfer_id INTEGER NOT NULL REFERENCES transfers(id) ON DELETE CASCADE,
                              state       VARCHAR(32) NOT NULL,
                              decision    VARCHAR(32),
                              decided_by  VARCHAR(100),
                              reason      TEXT,
                              risk_score  INTEGER,
                              assignee    VARCHAR(100),
                              tags        TEXT,
                              notes       TEXT,
                              created_at  TIMESTAMPTZ,
                              resolved_at TIMESTAMPTZ
);


CREATE SEQUENCE fraud_alerts_id_seq;
ALTER TABLE fraud_alerts
    ALTER COLUMN id SET DEFAULT nextval('fraud_alerts_id_seq');

ALTER SEQUENCE fraud_alerts_id_seq OWNED BY fraud_alerts.id;

-- One alert per transfer. There are two creation sites and the second is guarded by a read
-- rather than by a lock, so without this a race could file two - and two alerts make an
-- approved payment permanently unconfirmable. The constraint brings its own index, so no
-- separate one on transfer_id is needed. Nothing filters on state in SQL: the queue loads every
-- alert and filters in Java, so an index there served nothing.
ALTER TABLE fraud_alerts ADD CONSTRAINT fraud_alerts_one_per_transfer UNIQUE (transfer_id);
