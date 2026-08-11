package cz.vsb.minibank.uow;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.demo.DemoScenario;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.NotFoundException;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL-backed integration tests for UnitOfWork + repositories (PostgreSQL).
 * <p>
 * These need a live PostgreSQL with {@code db/init/schema.sql} applied. Without one the whole
 * class is reported as skipped, so a plain {@code mvn test} on a fresh clone stays green.
 * See {@link TestDatabase} for the connection keys and how to create the database.
 */
public class MinibankSqlUowTests {

    private String jdbcUrl;
    private String dbUser;
    private String dbPass;

    private Bootstrap infra;

    /** Probed once for the class; the assumption itself is per test so the skips are reported. */
    private static boolean databaseReachable;

    @BeforeAll
    static void probeTestDatabase() {
        TestDatabase.requireSeparateFromApplicationDatabase();
        databaseReachable = TestDatabase.isReachable();
    }

    @BeforeEach
    void setUp() throws Exception {
        // Aborting here rather than in @BeforeAll: a class-level assumption cancels the whole
        // container, and surefire then reports zero tests, which reads as "there are none".
        Assumptions.assumeTrue(databaseReachable, TestDatabase::unreachableMessage);

        jdbcUrl = TestDatabase.url();
        dbUser  = TestDatabase.user();
        dbPass  = TestDatabase.password();

        // Clean database state before each test (but keep schema & sequences)
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             Statement st = conn.createStatement()) {

            st.execute("""
                    TRUNCATE TABLE fraud_alerts, transfers, beneficiaries, accounts, customers
                    RESTART IDENTITY CASCADE
                    """);
        }

        // SQL-mode bootstrap
        infra = new Bootstrap(jdbcUrl, dbUser, dbPass);
    }

    // -------------------------------------------------------------------------
    // 1) IdentityMap for Account within a single SQL UnitOfWork
    // -------------------------------------------------------------------------

    @Test
    void identityMap_sameAccountInstanceWithinSqlUow() {
        UnitOfWork uow = infra.uowFactory.begin();
        int customerId;
        int accountId;

        try (UowScope __ = new UowScope(uow)) {
            // Create customer
            customerId = infra.customers.nextId();
            Customer c = new Customer(
                    customerId,
                    "SQL User",
                    "sql@example.com",
                    new Address("Street 1", "City")
            );
            infra.customers.save(c);

            // Create account
            accountId = infra.accounts.nextId();
            Account a = new Account(
                    accountId,
                    new IBAN("CZ6508000000192000145399"),
                    Money.czk(20_000),
                    Money.czk(5_000)
            );
            infra.accounts.save(a);

            c.addAccountId(accountId);
            infra.customers.save(c);

            // IdentityMap check: same instance within UoW
            Account a1 = infra.accounts.byId(accountId).orElseThrow();
            Account a2 = infra.accounts.byId(accountId).orElseThrow();

            assertSame(
                    a1, a2,
                    "Within a single SQL UoW Account instances must be served from IdentityMap"
            );

            uow.commit();
        }

        // New UoW: load persisted data from DB
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow2 = scope.uow();
            Account reloaded = infra.accounts.byId(accountId).orElseThrow();
            assertEquals(accountId, reloaded.id());
            assertEquals("CZ6508000000192000145399", reloaded.iban().value());
            assertEquals(0, Money.czk(20_000).amount().compareTo(reloaded.balance().amount()));
            uow2.commit();
        }
    }

    // -------------------------------------------------------------------------
    // 2) Customer + Beneficiary: aggregate update + persistence
    // -------------------------------------------------------------------------

    @Test
    void customerBeneficiaries_areUpdatedInAggregateAndPersisted() {
        int customerId;

        // First UoW: create a customer without beneficiaries
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow1 = scope.uow();
            customerId = infra.customers.nextId();
            Customer c = new Customer(
                    customerId,
                    "Lazy SQL User",
                    "lazy-sql@example.com",
                    new Address("Street 1", "City")
            );
            infra.customers.save(c);
            uow1.commit();
        }

        // Second UoW: load customer, add beneficiary via repository, commit
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow2 = scope.uow();
            Customer c = infra.customers.byId(customerId)
                    .orElseThrow(() -> new AssertionError("Customer must exist"));

            assertTrue(
                    c.beneficiaries().isEmpty(),
                    "Customer should initially have no beneficiaries"
            );

            int beneficiaryId = infra.customers.nextBeneficiaryId();
            Beneficiary b = new Beneficiary(
                    beneficiaryId,
                    "Alice SQL",
                    new IBAN("CZ1301000000000098765432"),
                    false
            );

            // This should update the aggregate in IdentityMap immediately and defer DB write to commit()
            infra.customers.saveBeneficiary(customerId, b);

            assertEquals(
                    1,
                    c.beneficiaries().size(),
                    "Customer aggregate in SQL UoW must reflect newly added beneficiary"
            );
            assertEquals(beneficiaryId, c.beneficiaries().get(0).id());
            assertEquals("Alice SQL", c.beneficiaries().get(0).name());

            uow2.commit();
        }

        // Third UoW: verify persistence in DB
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow3 = scope.uow();
            Customer reloaded = infra.customers.byId(customerId)
                    .orElseThrow(() -> new AssertionError("Customer must exist after commit"));

            assertEquals(
                    1,
                    reloaded.beneficiaries().size(),
                    "After commit, beneficiary must be persisted and loaded from DB"
            );
            Beneficiary b2 = reloaded.beneficiaries().get(0);
            assertEquals("Alice SQL", b2.name());
            assertEquals("CZ1301000000000098765432", b2.iban().value());

            uow3.commit();
        }
    }

    // -------------------------------------------------------------------------
    // 3) Transfer: persistence + IdentityMap with bySourceAccount
    // -------------------------------------------------------------------------

    @Test
    void transfer_isPersistedAndUsesIdentityMapForBySourceAccount() {
        int customerId;
        int accountId;
        int transferId;

        // UoW #1: create one customer + one account
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            customerId = infra.customers.nextId();
            Customer c = new Customer(
                    customerId,
                    "Transfer User",
                    "transfer@example.com",
                    new Address("Street 1", "City")
            );
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            Account a = new Account(
                    accountId,
                    new IBAN("CZ6508000000192000145399"),
                    Money.czk(10_000),
                    Money.czk(5_000)
            );
            infra.accounts.save(a);

            c.addAccountId(accountId);
            infra.customers.save(c);

            scope.uow().commit();
        }

        // UoW #2: create transfer and check IdentityMap via byId() twice
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            TransferRepository transfers = infra.transfers;

            transferId = transfers.nextId();
            Transfer t = new Transfer(
                    transferId,
                    accountId,
                    null,
                    "CZ0401000000000000000000",
                    Money.czk(1_000));

            transfers.add(t);

            // IdentityMap: byId() must return the same instance within the same UoW
            Transfer byId1 = transfers.byId(transferId)
                    .orElseThrow(() -> new AssertionError("Transfer must exist in UoW"));
            Transfer byId2 = transfers.byId(transferId)
                    .orElseThrow(() -> new AssertionError("Transfer must exist in UoW"));

            assertSame(
                    byId1,
                    byId2,
                    "Within a single SQL UoW, byId() must return the same Transfer instance from IdentityMap"
            );

            scope.uow().commit();
        }

        // UoW #3: after commit, verify persistence and IdentityMap across byId + bySourceAccount
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            // First, load by id -> goes to DB and populates IdentityMap
            Transfer fromId = infra.transfers.byId(transferId)
                    .orElseThrow(() -> new AssertionError("Transfer must be persisted in DB after commit"));

            // Then, load via bySourceAccount -> must reuse the same instance from IdentityMap
            List<Transfer> fromSource = infra.transfers.bySourceAccount(accountId);
            assertFalse(
                    fromSource.isEmpty(),
                    "bySourceAccount must return at least one transfer after commit"
            );

            Transfer fromList = fromSource.get(0);

            assertEquals(
                    transferId,
                    fromList.id(),
                    "bySourceAccount must return transfer with expected id"
            );

            assertSame(
                    fromId,
                    fromList,
                    "In a single SQL UoW, Transfer from byId() and bySourceAccount() must be the same instance (IdentityMap)"
            );

            scope.uow().commit();
        }
    }


    // -------------------------------------------------------------------------
    // 4) FraudAlert: save, byId, byTransferId, all()
    // -------------------------------------------------------------------------

    @Test
    void fraudAlert_isPersistedAndLoadableByIdAndTransferId() {
        int customerId;
        int accountId;
        int transferId;
        int alertId;

        // UoW #1: prepare minimal transfer to attach the alert to
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            customerId = infra.customers.nextId();
            Customer c = new Customer(
                    customerId,
                    "Fraud User",
                    "fraud@example.com",
                    new Address("Street 1", "City")
            );
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            Account a = new Account(
                    accountId,
                    new IBAN("CZ6508000000192000145399"),
                    Money.czk(5_000),
                    Money.czk(2_000)
            );
            infra.accounts.save(a);

            c.addAccountId(accountId);
            infra.customers.save(c);

            transferId = infra.transfers.nextId();
            Transfer t = new Transfer(
                    transferId,
                    accountId,
                    null,
                    "CZ0401000000000000000000",
                    Money.czk(500));
            infra.transfers.add(t);

            scope.uow().commit();
        }

        // UoW #2: create FraudAlert with metadata and test IdentityMap via byId() twice
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            FraudAlertRepository alerts = infra.alerts;

            alertId = alerts.nextId();

            FraudAlert alert = new FraudAlert(
                    alertId,
                    transferId,
                    "Suspicious SQL transfer",
                    87,                               // riskScore
                    "fraud-analyst-1",               // assignee
                    List.of("HIGH_AMOUNT", "NEW_BENEFICIARY"),
                    "Initial note from SQL test"
            );

            alerts.add(alert);

            FraudAlert a1 = alerts.byId(alertId)
                    .orElseThrow(() -> new AssertionError("FraudAlert must exist in UoW"));
            FraudAlert a2 = alerts.byId(alertId)
                    .orElseThrow(() -> new AssertionError("FraudAlert must exist in UoW"));

            assertSame(
                    a1,
                    a2,
                    "Within a single SQL UoW, byId() must return the same FraudAlert instance from IdentityMap"
            );

            assertEquals(87, a1.riskScore());
            assertEquals("fraud-analyst-1", a1.assignee());
            assertEquals(List.of("HIGH_AMOUNT", "NEW_BENEFICIARY"), a1.tags());
            assertEquals("Initial note from SQL test", a1.notes());

            scope.uow().commit();
        }

        // UoW #3: after commit, verify persistence + IdentityMap across byId() and byTransferId()
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            FraudAlertRepository alerts = infra.alerts;

            // Load by id -> populates IdentityMap
            FraudAlert byId = alerts.byId(alertId)
                    .orElseThrow(() -> new AssertionError("FraudAlert must be persisted in DB"));

            // Load by transferId -> must reuse the same instance from IdentityMap
            FraudAlert byTransfer = alerts.byTransferId(transferId)
                    .orElseThrow(() -> new AssertionError("FraudAlert must be loadable by transferId after commit"));

            assertSame(
                    byId,
                    byTransfer,
                    "Within a single SQL UoW, FraudAlert from byId() and byTransferId() must be the same instance (IdentityMap)"
            );

            // all() should also contain this alert
            List<FraudAlert> all = alerts.all();
            assertFalse(all.isEmpty(), "all() should return at least one FraudAlert");
            assertTrue(
                    all.stream().anyMatch(a -> a.id() == alertId),
                    "all() must contain our persisted FraudAlert"
            );

            assertEquals(87, byId.riskScore());
            assertEquals("fraud-analyst-1", byId.assignee());
            assertEquals(List.of("HIGH_AMOUNT", "NEW_BENEFICIARY"), byId.tags());
            assertEquals("Initial note from SQL test", byId.notes());

            scope.uow().commit();
        }
    }


// -------------------------------------------------------------------------
// 5) Account.byCustomerId uses IdentityMap within a single SQL UnitOfWork
// -------------------------------------------------------------------------

    @Test
    void accountsByCustomerId_usesIdentityMapWithinSqlUow() {
        int customerId;
        int accountId;

        // UoW #1: create customer + account
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow1 = scope.uow();
            customerId = infra.customers.nextId();
            Customer c = new Customer(
                    customerId,
                    "ByCustomer User",
                    "bycustomer@example.com",
                    new Address("Street 1", "City")
            );
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            Account a = new Account(
                    accountId,
                    new IBAN("CZ6508000000192000145399"),
                    Money.czk(15_000),
                    Money.czk(3_000)
            );
            infra.accounts.save(a);

            c.addAccountId(accountId);
            infra.customers.save(c);

            uow1.commit();
        }

        // UoW #2: first load byId, then byCustomerId -> must reuse same instance from IdentityMap
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow2 = scope.uow();
            Account byId = infra.accounts.byId(accountId)
                    .orElseThrow(() -> new AssertionError("Account must exist"));

            List<Account> list = infra.accounts.byCustomerId(customerId);
            assertFalse(list.isEmpty(), "byCustomerId must return at least one account");
            Account fromList = list.get(0);

            assertSame(
                    byId,
                    fromList,
                    "Within a single SQL UoW, Account from byId() and byCustomerId() must be the same instance (IdentityMap)"
            );

            uow2.commit();
        }
    }

// -------------------------------------------------------------------------
// 6) Rollback: no data should be persisted if UoW is rolled back
// -------------------------------------------------------------------------

    @Test
    void rollback_preventsPersistingCustomerAndAccount() {
        int customerId;
        int accountId;

        // UoW #1: create customer + account, but rollback instead of commit
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow1 = scope.uow();
            customerId = infra.customers.nextId();
            Customer c = new Customer(
                    customerId,
                    "Rollback User",
                    "rollback@example.com",
                    new Address("Street 1", "City")
            );
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            Account a = new Account(
                    accountId,
                    new IBAN("CZ6508000000192000145399"),
                    Money.czk(7_000),
                    Money.czk(2_000)
            );
            infra.accounts.save(a);

            c.addAccountId(accountId);
            infra.customers.save(c);

            // explicit rollback: nothing from this UoW should hit the database. Kept although
            // close() would roll back anyway, because this is the subject of the test rather
            // than its cleanup.
            uow1.rollback();
        }

        // UoW #2: verify that there is no such customer/account in DB
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow2 = scope.uow();
            assertTrue(
                    infra.customers.byId(customerId).isEmpty(),
                    "Customer must not be persisted if UoW was rolled back"
            );

            assertTrue(
                    infra.accounts.byId(accountId).isEmpty(),
                    "Account must not be persisted if UoW was rolled back"
            );

            uow2.commit();
        }
    }

// -------------------------------------------------------------------------
// 7) Money precision: NUMERIC(14,2) <-> Money must preserve cents
// -------------------------------------------------------------------------

    @Test
    void moneyPrecision_isPreservedBetweenDomainAndSql() {
        int customerId;
        int accountId;

        Money initialBalance = Money.czk(12345.67);
        Money initialLimit   = Money.czk(  890.12);

        // UoW #1: create customer + account with non-trivial monetary values
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            customerId = infra.customers.nextId();
            Customer c = new Customer(
                    customerId,
                    "Money User",
                    "money@example.com",
                    new Address("Street 1", "City")
            );
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            Account a = new Account(
                    accountId,
                    new IBAN("CZ6508000000192000145399"),
                    initialBalance,
                    initialLimit
            );
            infra.accounts.save(a);

            c.addAccountId(accountId);
            infra.customers.save(c);

            scope.uow().commit();
        }

        // UoW #2: reload account and compare monetary values
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow2 = scope.uow();
            Account reloaded = infra.accounts.byId(accountId)
                    .orElseThrow(() -> new AssertionError("Account must be persisted in DB"));

            assertEquals(
                    0,
                    initialBalance.amount().compareTo(reloaded.balance().amount()),
                    "Balance must be preserved with cents between domain Money and SQL NUMERIC"
            );

            assertEquals(
                    0,
                    initialLimit.amount().compareTo(reloaded.dailyLimit().amount()),
                    "Daily limit must be preserved with cents between domain Money and SQL NUMERIC"
            );

            uow2.commit();
        }
    }

// -------------------------------------------------------------------------
// 8) Ownership rests on accounts.customer_id, which only SQL mode has
// -------------------------------------------------------------------------

    /**
     * In SQL mode {@code Customer.accountIds()} is {@code SELECT id FROM accounts WHERE
     * customer_id = ?}, so that one column is the sole record of ownership and the only thing
     * OwnershipGuard can read. Nothing else in the suite exercises the guard against a real
     * database: the JSON tests cannot see this column, and SqlAccountRepository deliberately
     * writes it NULL, leaving SqlCustomerRepository.upsertCustomer as the only writer.
     */
    @Test
    void ownershipIsAnsweredFromAccountsCustomerIdInSqlMode() {
        BootstrapServices services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts, infra.uowFactory);

        int ownerId = new DemoScenario(
                infra.customers, infra.accounts, infra.transfers, infra.alerts,
                infra.uowFactory, services.feePolicy).seed();

        int ownedAccount;
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow = scope.uow();
            Customer owner = infra.customers.byId(ownerId).orElseThrow();

            assertFalse(owner.accountIds().isEmpty(),
                    "Seeding must have assigned accounts.customer_id, or every money path 404s");
            assertEquals(
                    owner.accountIds().stream().sorted().toList(),
                    infra.accounts.byCustomerId(ownerId).stream().map(Account::id).sorted().toList(),
                    "The guard's view of ownership and byCustomerId must be the same set");

            ownedAccount = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow().id();
            uow.commit();
        }

        // A stranger: a real customer row with an account of its own, so the refusal below is
        // the ownership rule and not a missing caller.
        int strangerId;
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            strangerId = infra.customers.nextId();
            Customer stranger = new Customer(strangerId, "Stranger", "stranger@example.com",
                    new Address("Elsewhere 1", "Brno"));
            infra.customers.save(stranger);

            int strangerAccount = infra.accounts.nextId();
            infra.accounts.save(new Account(strangerAccount, new IBAN("CZ7408000000192000145431"),
                    Money.czk(20_000), Money.czk(5_000)));
            stranger.addAccountId(strangerAccount);
            infra.customers.save(stranger);

            scope.uow().commit();
        }

        Money victimBefore = infra.accounts.byId(ownedAccount).orElseThrow().balance();

        assertThrows(NotFoundException.class, () -> services.transferService.submitPaymentToIban(
                strangerId, ownedAccount, "CZ2001000000000012345678", 900.0, "not my account"));
        assertEquals(0, victimBefore.amount().compareTo(
                        infra.accounts.byId(ownedAccount).orElseThrow().balance().amount()),
                "The refused payment must not have debited the owner");

        // The owner's own path still works end to end against a real database.
        int transferId = services.transferService.submitPaymentToIban(
                ownerId, ownedAccount, "CZ2001000000000012345678", 100.0, "mine").transferId();
        assertEquals(TransferStatus.SENT, infra.transfers.byId(transferId).orElseThrow().status());
    }

// -------------------------------------------------------------------------
// 9) The credit leg against a real database
// -------------------------------------------------------------------------

    /**
     * Both legs of an in-bank payment are written by one JDBC transaction.
     *
     * The JSON tests cannot show this: there the two upserts are closures over a shared
     * Bundle, while here they are two INSERT ... ON CONFLICT statements followed by one
     * connection.commit(). It is also the only place the SQL byIban probe is exercised on the
     * money path, since the destination is resolved by IBAN after the source has been loaded
     * by id - the order that used to put two instances of one row into the identity map.
     */
    @Test
    void anInBankTransferCreditsTheDestinationInOneSqlTransaction() {
        BootstrapServices services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts, infra.uowFactory);

        final IBAN payerIban = new IBAN("CZ6508000000192000145399");
        final IBAN payeeIban = new IBAN("CZ4308000000192000145407");
        final Money opening = Money.czk(20_000);

        int payerId;
        int payerAccount;
        int payeeAccount;

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            payerId = infra.customers.nextId();
            Customer payer = new Customer(payerId, "Payer", "payer@example.com",
                    new Address("Street 1", "City"));
            infra.customers.save(payer);
            payerAccount = infra.accounts.nextId();
            infra.accounts.save(new Account(payerAccount, payerIban, opening, Money.czk(20_000)));
            payer.addAccountId(payerAccount);
            // The second save is what writes accounts.customer_id; see DemoScenario.create.
            infra.customers.save(payer);

            int payeeId = infra.customers.nextId();
            Customer payee = new Customer(payeeId, "Payee", "payee@example.com",
                    new Address("Street 2", "City"));
            infra.customers.save(payee);
            payeeAccount = infra.accounts.nextId();
            infra.accounts.save(new Account(payeeAccount, payeeIban, opening, Money.czk(20_000)));
            payee.addAccountId(payeeAccount);
            infra.customers.save(payee);

            scope.uow().commit();
        }

        Money before = totalAccountMoney();
        assertEquals(0, opening.plus(opening).amount().compareTo(before.amount()));

        double amount = 5_000;
        Money charged = services.feePolicy.compute(Money.czk(amount));
        assertTrue(charged.isPositive(), "this case is only interesting with a fee to lose");

        int transferId = services.transferService.submitPaymentToIban(
                payerId, payerAccount, payeeIban.value(), amount, "in bank").transferId();

        assertEquals(TransferStatus.SENT, infra.transfers.byId(transferId).orElseThrow().status());
        assertEquals(0, opening.minus(Money.czk(amount)).minus(charged).amount().compareTo(
                        infra.accounts.byId(payerAccount).orElseThrow().balance().amount()),
                "the sender pays amount plus fee");
        assertEquals(0, opening.plus(Money.czk(amount)).amount().compareTo(
                        infra.accounts.byId(payeeAccount).orElseThrow().balance().amount()),
                "and the destination row really was updated, not just the sender's");
        assertEquals(0, before.minus(charged).amount().compareTo(totalAccountMoney().amount()),
                "the fee is the only money that may leave the system");
    }

// -------------------------------------------------------------------------
// 10) An ownership claim that matches no row must fail the commit
// -------------------------------------------------------------------------

    /**
     * accounts.customer_id is written by exactly one statement, SqlCustomerRepository's
     * {@code UPDATE accounts SET customer_id = ? WHERE id = ?}, and it is the only record of
     * ownership SQL mode has. A customer naming an account id with no row behind it makes that
     * statement match nothing, which JDBC does not consider an error, so the commit used to
     * succeed and leave an account owned by nobody, a customer that reloads with no accounts,
     * and every money path on it answering 404 without anything having reported a problem.
     */
    @Test
    void claimingAnAccountThatHasNoRowFailsTheCommit() {
        int customerId;

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow = scope.uow();

            customerId = infra.customers.nextId();
            Customer c = new Customer(customerId, "Claims Too Much", "claims@example.com",
                    new Address("Street 1", "City"));

            // A genuine id off the accounts sequence that no row is ever written for, which is
            // the shape a stale or simply wrong entry in accountIds has. The first id off that
            // sequence is discarded so this one cannot equal the customer's own: both sequences
            // restart with the truncate in setUp, and an assertion about a message naming "1"
            // would be answered by either of them.
            infra.accounts.nextId();
            int missingAccountId = infra.accounts.nextId();
            c.addAccountId(missingAccountId);
            infra.customers.save(c);

            DataIntegrityException refused = assertThrows(DataIntegrityException.class, uow::commit,
                    "A claim on an account with no row must fail the commit, not pass unnoticed");
            assertTrue(
                    refused.getMessage().contains(String.valueOf(missingAccountId)),
                    "The refusal must name the account it could not claim: " + refused.getMessage()
            );
        }

        // The customers row was written by the same method, one statement earlier, so a refusal
        // that did not take the whole transaction with it would be its own kind of half-write.
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            assertTrue(
                    infra.customers.byId(customerId).isEmpty(),
                    "The failed commit must have rolled the customer row back as well"
            );
            scope.uow().commit();
        }
    }

// -------------------------------------------------------------------------
// 11) The ordering the demo depends on still assigns ownership
// -------------------------------------------------------------------------

    /**
     * The counterpart to the case above, and the reason it has to be careful about what it
     * refuses. Every caller here saves a customer once before its accounts exist and again
     * afterwards, and it is that second save which writes accounts.customer_id - DemoScenario
     * says so, and says the money paths 404 without it. Checking the rows an ownership claim
     * touched must let this through untouched.
     */
    @Test
    void theSecondSaveStillAssignsOwnershipInTheDemoOrdering() {
        int customerId;
        int accountId;

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            customerId = infra.customers.nextId();
            Customer c = new Customer(customerId, "Ordinary Owner", "ordinary@example.com",
                    new Address("Street 1", "City"));
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            infra.accounts.save(new Account(accountId, new IBAN("CZ6508000000192000145399"),
                    Money.czk(9_000), Money.czk(3_000)));

            c.addAccountId(accountId);
            infra.customers.save(c);

            scope.uow().commit();
        }

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            Customer reloaded = infra.customers.byId(customerId)
                    .orElseThrow(() -> new AssertionError("Customer must exist after commit"));

            assertEquals(
                    List.of(accountId),
                    reloaded.accountIds(),
                    "The second save must have written accounts.customer_id"
            );
            assertEquals(
                    List.of(accountId),
                    infra.accounts.byCustomerId(customerId).stream().map(Account::id).toList(),
                    "and the account must answer to that owner from the other side"
            );

            scope.uow().commit();
        }
    }

    /** Sums balance_czk over every account row, so nothing can hide outside the fixture. */
    private Money totalAccountMoney() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT COALESCE(SUM(balance_czk), 0) AS total FROM accounts")) {
            rs.next();
            return Money.czk(rs.getBigDecimal("total"));
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("Failed to total account balances", e);
        }
    }

}
