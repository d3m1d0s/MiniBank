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

------------------------------------------------------------
-- ACCOUNTS
-- domain model: Account(id, iban, balance, dailyLimit, transferIds)
-- relation to Customer: via customer_id (persistence detail)
------------------------------------------------------------

CREATE TABLE accounts (
                          id               INTEGER PRIMARY KEY,
                          iban             VARCHAR(34) NOT NULL UNIQUE,
                          balance_czk      NUMERIC(14,2) NOT NULL,
                          daily_limit_czk  NUMERIC(14,2) NOT NULL,
                          customer_id      INTEGER REFERENCES customers(id) ON DELETE CASCADE
);

CREATE SEQUENCE accounts_id_seq;
ALTER TABLE accounts
    ALTER COLUMN id SET DEFAULT nextval('accounts_id_seq');

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

CREATE INDEX idx_beneficiaries_customer_id ON beneficiaries(customer_id);

------------------------------------------------------------
-- TRANSFERS
-- domain model: Transfer(
--   id,
--   sourceAccountId,
--   beneficiaryId,
--   targetIbanSnapshot,
--   Money amount,
--   String currency,
--   TransferStatus status,
--   Instant createdAt,
--   Payment authMethod,
--   String declineReason
-- )
------------------------------------------------------------

CREATE TABLE transfers (
                           id                   INTEGER PRIMARY KEY,
                           source_account_id    INTEGER NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
                           beneficiary_id       INTEGER REFERENCES beneficiaries(id) ON DELETE SET NULL,
                           target_iban_snapshot VARCHAR(34) NOT NULL,

                           amount               NUMERIC(14,2) NOT NULL,
                           currency             VARCHAR(3) NOT NULL,

                           status               VARCHAR(32) NOT NULL,
                           created_at           TIMESTAMPTZ,

                           auth_method          VARCHAR(32),
                           card_number_masked   VARCHAR(64),
                           decline_reason       TEXT,
                           auth_attempts        INTEGER,
                           auth_valid_until     TIMESTAMPTZ
);

CREATE SEQUENCE transfers_id_seq;
ALTER TABLE transfers
    ALTER COLUMN id SET DEFAULT nextval('transfers_id_seq');

CREATE INDEX idx_transfers_source_account ON transfers(source_account_id);
CREATE INDEX idx_transfers_beneficiary ON transfers(beneficiary_id);

------------------------------------------------------------
-- FRAUD ALERTS
-- domain model: FraudAlert(id, transferId, FraudAlertState, ...)
-- plus RiskDecision (enum), reason/message, timestamps
------------------------------------------------------------

CREATE TABLE fraud_alerts (
                              id          INTEGER PRIMARY KEY,
                              transfer_id INTEGER NOT NULL REFERENCES transfers(id) ON DELETE CASCADE,
                              state       VARCHAR(32) NOT NULL,
                              decision    VARCHAR(32),
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

CREATE INDEX idx_fraud_alerts_transfer ON fraud_alerts(transfer_id);
CREATE INDEX idx_fraud_alerts_state ON fraud_alerts(state);
