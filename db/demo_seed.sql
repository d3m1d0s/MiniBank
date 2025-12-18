BEGIN;

-- Customer used by demo login: alice has customerId = 2
INSERT INTO customers (id, name, email, street, city)
VALUES (2, 'Alice Demo', 'alice@example.com', 'Demo Street 1', 'Ostrava')
ON CONFLICT (id) DO NOTHING;

-- Two accounts for customer 2 so /api/me/accounts has something to show
INSERT INTO accounts (id, iban, balance_czk, daily_limit_czk, customer_id)
VALUES
    (10, 'CZ6508000000192000145399', 25000.00, 15000.00, 2),
    (11, 'CZ6508000000192000145407',  5000.00,  8000.00, 2)
ON CONFLICT (id) DO NOTHING;

-- Beneficiaries for customer 2
INSERT INTO beneficiaries (id, customer_id, name, iban, trusted)
VALUES
    (20, 2, 'Bob Trusted',    'CZ6508000000192000145415', TRUE),
    (21, 2, 'Mallory Risky',  'CZ6508000000192000145423', FALSE)
ON CONFLICT (id) DO NOTHING;

-- One transfer so history is not empty (safe: SENT, no auth_method needed)
INSERT INTO transfers (
    id, source_account_id, beneficiary_id, target_iban_snapshot,
    amount, currency, status, created_at,
    auth_method, card_number_masked, decline_reason, auth_attempts, auth_valid_until
)
VALUES (
           100, 10, 20, 'CZ6508000000192000145415',
           1500.00, 'CZK', 'SENT', now() - interval '2 days',
           NULL, NULL, NULL, 0, NULL
       )
ON CONFLICT (id) DO NOTHING;

-- One fraud alert so fraud analyst sees something immediately
INSERT INTO fraud_alerts (
    id, transfer_id, state, decision, reason, risk_score, assignee, tags, notes, created_at, resolved_at
)
VALUES (
           200, 100, 'NEW', NULL, 'Demo alert for UI', 70, NULL, 'demo,seed', 'Seeded record', now() - interval '1 day', NULL
       )
ON CONFLICT (id) DO NOTHING;

-- Fix sequences so nextId() does not collide with our explicit ids
SELECT setval('customers_id_seq',     COALESCE((SELECT MAX(id) FROM customers), 1));
SELECT setval('accounts_id_seq',      COALESCE((SELECT MAX(id) FROM accounts), 1));
SELECT setval('beneficiaries_id_seq', COALESCE((SELECT MAX(id) FROM beneficiaries), 1));
SELECT setval('transfers_id_seq',     COALESCE((SELECT MAX(id) FROM transfers), 1));
SELECT setval('fraud_alerts_id_seq',  COALESCE((SELECT MAX(id) FROM fraud_alerts), 1));
SELECT setval('users_id_seq',         COALESCE((SELECT MAX(id) FROM users), 1));

COMMIT;
