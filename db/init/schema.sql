-- Creates the MiniBank schema in an empty database. It drops nothing, so running it
-- against a database that already has these tables fails instead of destroying them.
-- To start over on purpose, run ../reset.sql.

------------------------------------------------------------
-- CUSTOMERS
-- domain model: Customer(id, name, email, Address address, Money dailyLimit,
--                        Money softDailyThreshold)
-- relation to accounts and beneficiaries: those tables carry customer_id
--
-- The two limits sit here and not on the account, which is where they used to sit. A ceiling per
-- account is a ceiling a customer with two accounts does not have: they pay half out of each and
-- spend the sum of two allowances, and can fund the second half by first moving money between
-- their own accounts. See db/migrate/2026-08-24-daily-limit-on-the-customer.sql for the move.
------------------------------------------------------------

CREATE TABLE customers (
                           id      INTEGER PRIMARY KEY,
                           name    VARCHAR(255) NOT NULL,
                           email   VARCHAR(255) NOT NULL,
                           street  VARCHAR(255) NOT NULL,
                           city    VARCHAR(255) NOT NULL,

                           -- The hard ceiling on what may leave this customer in one day, fees
                           -- excluded. What counts against it is everything sent out of any
                           -- account they hold, less what only landed on another account of
                           -- theirs: money that moved between their own accounts has not left
                           -- them, and counting it would let the ceiling be inflated by shuffling
                           -- money in place. NOT NULL because a customer with no ceiling is a
                           -- customer nothing bounds, and Customer's constructor refuses one.
                           daily_limit_czk           NUMERIC(14,2) NOT NULL,

                           -- NULL means "no override": RuleBasedRiskService applies the bank-wide
                           -- soft tier. A value below daily_limit_czk is what makes the customer
                           -- genuinely two-tiered; at or above it the soft tier is inert, which is
                           -- deliberately still representable - a bank may want a customer with
                           -- one tier. No CHECK relates the two.
                           soft_daily_threshold_czk  NUMERIC(14,2)
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
-- domain model: Account(id, iban, balance)
-- relation to Customer: via customer_id (persistence detail)
--
-- The daily ceiling and the soft authorization tier were columns here until they moved to
-- customers, where one ceiling covers everything the owner holds. An account is now what it
-- says: an IBAN and a balance.
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
--   String message,
--   TransferStatus status,
--   Instant createdAt,
--   Instant settledAt,
--   DispatchState dispatchState,
--   Payment authMethod,
--   String declineReason
-- )
--
-- fee is the fee actually charged, written once by Transfer.send. NULL until the transfer
-- settles, and NULL forever on one that never did. Not recomputed on display, which is the
-- whole point of storing it: swapping the FeePolicy bean must not silently restate what a
-- customer was charged last month. That is also why the sign is checked here and nowhere
-- else - see the CHECK below.
--
-- settled_at is when the money moved; created_at is when the order was placed. The daily
-- total is keyed on settled_at, falling back to created_at for rows written before this
-- column existed - see SqlTransferRepository.sumLeavingCustomerWithConnection.
--
-- That total is now the owner's rather than one account's, and target_iban_snapshot is what
-- decides which rows belong in it: a row counts when its destination is not one of the
-- customer's own accounts. Money moved between two accounts of the same owner never left
-- them, and counting it would let the daily ceiling be consumed, or inflated, by shuffling
-- money in place. The snapshot rather than beneficiaries.iban, because the snapshot is what
-- the payment was actually addressed to and it survives the payee being edited or deleted.
--
-- dispatch_state is what a settled payment still owes the payment network, and it is on this
-- row rather than in an outbox table of its own for one reason: the row a separate table would
-- carry a foreign key to already holds the payload, because the gateway takes the whole
-- aggregate. Written in the same transaction as the debit, so the obligation and the money
-- movement that creates it commit together or not at all.
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
                           -- NOT NULL because the loader already treats it as such:
                           -- Transfer.hydrateForLoad refuses a row without a creation instant
                           -- rather than stamping the load instant over it. Everything this
                           -- application writes stamps the column unconditionally, so the only
                           -- producer of a NULL was a writer outside the domain - the same writer
                           -- the CHECKs below were added against - and what it left behind was an
                           -- unreadable row rather than a merely wrong one.
                           created_at           TIMESTAMPTZ NOT NULL,
                           settled_at           TIMESTAMPTZ,

                           -- PENDING once a payment has settled out of this bank, DISPATCHED once
                           -- a gateway has been handed it. NULL is the third value and the common
                           -- one: it says this payment owes the network nothing, which covers
                           -- every intra-bank transfer, everything that has not settled, and every
                           -- row written before this column existed. Nullable rather than NOT NULL
                           -- with a NONE default precisely so that no existing row has to be given
                           -- a value somebody would have to decide - see
                           -- db/migrate/transfer-dispatch-state.sql for what backfilling those
                           -- rows would send twice. Only Transfer.send writes the pending value and
                           -- it assigns SENT in the same call, so a row carrying one is a SENT row.
                           dispatch_state       VARCHAR(32),

                           auth_method          VARCHAR(32),
                           card_number_masked   VARCHAR(64),
                           decline_reason       TEXT,
                           -- When the payment was refused, beside the sentence saying why. The
                           -- pair is one fact read in two halves, and for a long time only the
                           -- second half was kept: a customer could read why their payment had
                           -- been stopped and never when. NULL on everything that has not been
                           -- refused, and on every refused row written before this column, whose
                           -- instant was never recorded anywhere a query can reach - the status
                           -- transitions went to a text log that nothing reads back. See
                           -- db/migrate/2026-08-29-declined-at-on-the-transfer.sql for why
                           -- created_at was not used to fill those in.
                           --
                           -- NOT settled_at, which is here already and nullable already. That
                           -- column is when the money moved, which is what settlement means in
                           -- this trade, and a refused payment moved none.
                           declined_at          TIMESTAMPTZ,
                           auth_attempts        INTEGER,
                           auth_valid_until     TIMESTAMPTZ,

                           -- Bumped by every guarded write, exactly as accounts.version is.
                           -- The version went to accounts alone at first, because that is
                           -- where the measured leak was. This row needs its own: cancelPayment
                           -- and the two authorization failure paths write the transfer and
                           -- never touch accounts, so accounts.version cannot see them.
                           version              INTEGER NOT NULL DEFAULT 0,

                           -- The same invariant Transfer's constructor enforces, now also
                           -- enforced against anything that writes this table without going
                           -- through the domain: psql, a future report job, a bad migration.
                           -- Named explicitly so a migrated database and a fresh one carry the
                           -- same constraint name.
                           CONSTRAINT transfers_amount_positive CHECK (amount > 0),

                           -- One currency, and here it stops being a convention. The domain
                           -- states it in Transfer's constructor, both loaders rebuild the
                           -- amount from this column, and this refuses a row that names any
                           -- other. Without it the constraint lives only in Java and psql is a
                           -- way around it - which is precisely how a foreign row could be
                           -- written at all.
                           CONSTRAINT transfers_currency_czk CHECK (currency = 'CZK'),

                           -- The fee is the one stored number nothing re-checks on the way out.
                           -- Transfer.hydrateSettlement validates nothing, deliberately, so a
                           -- hand-written negative fee loads on both backends and reaches the
                           -- details endpoint and the fraud desk through Transfer.feeFor: the
                           -- receipt then understates the historical debit and stops reconciling
                           -- with the balance movement. FeePolicy's contract already says a fee is
                           -- never negative; this is where a row that was not written through a
                           -- FeePolicy is held to it.
                           --
                           -- NULL is left alone on purpose and the predicate is written so it
                           -- passes: an unsettled transfer has been charged nothing yet, which is
                           -- a different fact from being charged zero, and a CHECK admits a row
                           -- whose predicate is unknown.
                           CONSTRAINT transfers_fee_not_negative CHECK (fee >= 0),

                           -- Both loaders refuse a dispatch state they cannot read rather than
                           -- reading it as absent, because absent is the lenient answer: it drops
                           -- a payment that has left the bank out of the only query that will ever
                           -- hand it to the network. This is where such a value is refused at
                           -- write time instead, and it faces the same out-of-domain writer the
                           -- CHECKs above were added for.
                           --
                           -- NULL passes, exactly as it does on the fee and for the same mechanism:
                           -- a CHECK admits a row whose predicate is unknown, and here unknown is
                           -- the normal case rather than the exception.
                           CONSTRAINT transfers_dispatch_state_known
                               CHECK (dispatch_state IN ('PENDING', 'DISPATCHED')),

                           -- Only a refused payment may carry a refusal instant, held against the
                           -- same out-of-domain writer the CHECKs above were added for. Transfer
                           -- assigns the two in one call, so a row that carries the instant under
                           -- any other status was not written through the domain.
                           --
                           -- NULL passes, by the same mechanism as the fee and the dispatch state:
                           -- a CHECK admits a row whose predicate is unknown. Here that is the
                           -- common case - every payment that has not been refused - rather than
                           -- the exception.
                           CONSTRAINT transfers_declined_at_only_when_declined
                               CHECK (declined_at IS NULL OR status = 'DECLINED')
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
-- table was created and were written by nothing and read by nothing; wiring them up is what
-- decided_by arrives with, rather than arriving alone.
-- decided_by is NULL for a decision made from the console, which has no login.
--
-- version is not a domain field either, exactly as it is not one on accounts and transfers: it
-- is the optimistic-lock token, see SqlFraudAlertRepository.upsertAlert. The JSON backend has
-- none and needs none.
------------------------------------------------------------

CREATE TABLE fraud_alerts (
                              id          INTEGER PRIMARY KEY,
                              transfer_id INTEGER NOT NULL REFERENCES transfers(id) ON DELETE CASCADE,
                              state       VARCHAR(32) NOT NULL,
                              decision    VARCHAR(32),
                              decided_by  VARCHAR(100),
                              -- Why the rules raised this alert, and nothing else. An analyst's
                              -- own comment used to be appended into it behind a " | ", so one
                              -- line carried two facts with two different authors and no reader
                              -- could tell them apart. That comment now has the column below.
                              reason      TEXT,

                              -- What the analyst wrote when they decided it, on any of the three
                              -- decisions and not only on a refusal. Null on an open alert and on
                              -- a verdict taken without a word, which is the common case on an
                              -- APPROVE. Replaced by a later decision, exactly as decision,
                              -- decided_by and resolved_at are; anything that has to survive goes
                              -- in fraud_alert_notes below, which is append only.
                              decision_comment TEXT,

                              risk_score  INTEGER,
                              assignee    VARCHAR(100),
                              tags        TEXT,
                              -- NOT NULL for the same reason transfers.created_at is, and the
                              -- blast radius here is wider than one row: FraudAlert.hydrateForLoad
                              -- refuses an alert with no creation instant, and
                              -- SqlFraudAlertRepository loads the whole queue and maps every row,
                              -- so a single NULL written by hand turns the analyst queue into a
                              -- 500 until somebody goes and finds it.
                              created_at  TIMESTAMPTZ NOT NULL,
                              resolved_at TIMESTAMPTZ,

                              -- Bumped by every guarded write, exactly as accounts.version and
                              -- transfers.version are. This row needs its own because FraudAlert's
                              -- state guards are checked against each transaction's own snapshot
                              -- and the write is deferred to commit: on every analyst action that
                              -- does not also write the transfers row - a verdict on a payment
                              -- already SENT or DECLINED, and the annotate route, which changes no
                              -- state at all - transfers.version sees nothing and the last commit
                              -- simply won, which is how an APPROVE could bury a DECLINE and an
                              -- annotation could write NEW back over a verdict.
                              version     INTEGER NOT NULL DEFAULT 0
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


-- The analyst's notes journal: one row per note, appended and never touched again.
--
-- It replaces a single notes column on fraud_alerts, which every save overwrote. Two analysts
-- working the same alert wrote over each other with nothing telling either of them, and the
-- column recorded neither who had written what nor when. On a queue that is shared work by
-- construction, that is a case file that quietly loses evidence.
--
-- There is no UPDATE and no DELETE anywhere above this table. The repository exposes exactly two
-- operations, append and read, which is what makes "nothing overwrites, ever" a property of the
-- store rather than a habit of its callers.
CREATE TABLE fraud_alert_notes (
    id         INTEGER PRIMARY KEY,
    alert_id   INTEGER NOT NULL REFERENCES fraud_alerts(id) ON DELETE CASCADE,

    -- The analyst who wrote it. NULL only on the entry the migration carried over from the old
    -- notes column, which recorded no author; a placeholder there would name somebody who never
    -- wrote anything.
    author     VARCHAR(100),

    -- NOT NULL for the reason fraud_alerts.created_at is: the journal is ordered by it, and one
    -- NULL written by hand would put an entry somewhere no reader can predict.
    written_at TIMESTAMPTZ NOT NULL,

    -- CHECKed rather than merely NOT NULL: an entry that says nothing is not a fact about the
    -- case, and both write paths already drop a blank instead of storing one.
    text       TEXT NOT NULL CHECK (btrim(text) <> '')
);

CREATE SEQUENCE fraud_alert_notes_id_seq;
ALTER TABLE fraud_alert_notes
    ALTER COLUMN id SET DEFAULT nextval('fraud_alert_notes_id_seq');

ALTER SEQUENCE fraud_alert_notes_id_seq OWNED BY fraud_alert_notes.id;

-- The one read this table serves: one alert's journal in the order it was written. The id is in
-- the index because two notes can share an instant and offsetting over a partial order is what
-- repeats one row and drops another.
CREATE INDEX fraud_alert_notes_by_alert ON fraud_alert_notes (alert_id, written_at, id);
