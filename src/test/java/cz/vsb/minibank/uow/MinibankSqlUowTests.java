package cz.vsb.minibank.uow;

import cz.vsb.minibank.application.config.BootstrapServices;
import cz.vsb.minibank.demo.DemoScenario;
import cz.vsb.minibank.domain.customer.*;
import cz.vsb.minibank.domain.fraud.*;
import cz.vsb.minibank.domain.transfer.*;
import cz.vsb.minibank.domain.exceptions.ConflictException;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.NotFoundException;
import cz.vsb.minibank.domain.exceptions.OptimisticLockException;
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
import java.util.ArrayList;
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
                    TRUNCATE TABLE fraud_alert_notes, fraud_alerts, transfers, beneficiaries, accounts, customers
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
                    new Address("Street 1", "City"),
                    Money.czk(5_000)
            );
            infra.customers.save(c);

            // Create account
            accountId = infra.accounts.nextId();
            Account a = new Account(
                    accountId,
                    new IBAN("CZ6508000000192000145399"),
                    Money.czk(20_000)
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
                    new Address("Street 1", "City"),
                    Money.czk(5_000)
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
                    new Address("Street 1", "City"),
                    Money.czk(5_000)
            );
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            Account a = new Account(
                    accountId,
                    new IBAN("CZ6508000000192000145399"),
                    Money.czk(10_000)
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
                    new Address("Street 1", "City"),
                    Money.czk(2_000)
            );
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            Account a = new Account(
                    accountId,
                    new IBAN("CZ6508000000192000145399"),
                    Money.czk(5_000)
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
                    List.of("HIGH_AMOUNT", "NEW_BENEFICIARY")
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
                    new Address("Street 1", "City"),
                    Money.czk(3_000)
            );
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            Account a = new Account(
                    accountId,
                    new IBAN("CZ6508000000192000145399"),
                    Money.czk(15_000)
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
                    new Address("Street 1", "City"),
                    Money.czk(2_000)
            );
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            Account a = new Account(
                    accountId,
                    new IBAN("CZ6508000000192000145399"),
                    Money.czk(7_000)
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
                    new Address("Street 1", "City"),
                    initialLimit
            );
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            Account a = new Account(
                    accountId,
                    new IBAN("CZ6508000000192000145399"),
                    initialBalance
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

            // The ceiling is on the customer row now, so the second half of this round trip is
            // asked of the customer. Same property, different table.
            Customer reloadedOwner = infra.customers.byId(customerId)
                    .orElseThrow(() -> new AssertionError("Customer must be persisted in DB"));

            assertEquals(
                    0,
                    initialLimit.amount().compareTo(reloadedOwner.dailyLimit().amount()),
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
                    new Address("Elsewhere 1", "Brno"), Money.czk(5_000));
            infra.customers.save(stranger);

            int strangerAccount = infra.accounts.nextId();
            infra.accounts.save(new Account(strangerAccount, new IBAN("CZ7408000000192000145431"),
                    Money.czk(20_000)));
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
                    new Address("Street 1", "City"), Money.czk(20_000));
            infra.customers.save(payer);
            payerAccount = infra.accounts.nextId();
            infra.accounts.save(new Account(payerAccount, payerIban, opening));
            payer.addAccountId(payerAccount);
            // The second save is what writes accounts.customer_id; see DemoScenario.create.
            infra.customers.save(payer);

            int payeeId = infra.customers.nextId();
            Customer payee = new Customer(payeeId, "Payee", "payee@example.com",
                    new Address("Street 2", "City"), Money.czk(20_000));
            infra.customers.save(payee);
            payeeAccount = infra.accounts.nextId();
            infra.accounts.save(new Account(payeeAccount, payeeIban, opening));
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
                    new Address("Street 1", "City"), Money.czk(5_000));

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
                    new Address("Street 1", "City"), Money.czk(3_000));
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            infra.accounts.save(new Account(accountId, new IBAN("CZ6508000000192000145399"),
                    Money.czk(9_000)));

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

// -------------------------------------------------------------------------
// 12) Collection reads answer in a defined order, not in the heap's
// -------------------------------------------------------------------------

    /** One account with some transfers on it, ids as the sequences handed them out. */
    private record OrderingFixture(int accountId, List<Integer> transferIds) { }

    private OrderingFixture seedAccountWithTransfers(int count) {
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            int customerId = infra.customers.nextId();
            Customer c = new Customer(customerId, "Ordering User", "ordering@example.com",
                    new Address("Street 1", "City"), Money.czk(50_000));
            infra.customers.save(c);

            int accountId = infra.accounts.nextId();
            infra.accounts.save(new Account(accountId, new IBAN("CZ6508000000192000145399"),
                    Money.czk(50_000)));
            c.addAccountId(accountId);
            infra.customers.save(c);

            List<Integer> transferIds = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int transferId = infra.transfers.nextId();
                infra.transfers.add(new Transfer(
                        transferId, accountId, null, "CZ0401000000000000000000", Money.czk(100 + i)));
                transferIds.add(transferId);
            }

            scope.uow().commit();
            return new OrderingFixture(accountId, List.copyOf(transferIds));
        }
    }

    /**
     * A rewritten transfer must not move in the customer's list.
     *
     * The rewrite in the middle is what makes this discriminate. Rows read straight back off a
     * freshly truncated table come out in insertion order on either backend, so an insert-and-read
     * case passes whether or not the statement is ordered; an UPDATE writes a new tuple version
     * wherever the page has room, which here is behind every other row, and an unordered SELECT
     * then answers with that transfer last. One wrong OTP attempt on a waiting payment is enough
     * to cause it, and AuthorizationController.waitingFor renders repository order as it stands.
     */
    @Test
    void transfersOfOneAccountStayInIdOrderWhenOneIsRewritten() {
        OrderingFixture fixture = seedAccountWithTransfers(5);

        // The earliest row, so its new tuple version lands behind all four others.
        int rewritten = fixture.transferIds().get(0);

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            Transfer t = infra.transfers.byId(rewritten)
                    .orElseThrow(() -> new AssertionError("Seeded transfer must exist"));
            t.requestAuthorization(new CardPayment(t.amount(), "**** **** **** 4242"));
            t.registerFailedOtpAttempt(3, Instant.now());
            infra.transfers.save(t);
            scope.uow().commit();
        }

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            List<Integer> ids = infra.transfers.bySourceAccount(fixture.accountId())
                    .stream().map(Transfer::id).toList();

            assertEquals(
                    fixture.transferIds().stream().sorted().toList(),
                    ids,
                    "bySourceAccount must answer in ascending id order, not in whatever order the"
                            + " heap holds once a row has been rewritten"
            );

            scope.uow().commit();
        }
    }

    /**
     * A rewritten alert must not move in the analyst's queue.
     *
     * The same rule as the case above, on the statement behind FraudController.buildQueue, and it
     * needs a case of its own because the two live in different repositories. Taking an alert
     * into a name is the cheapest rewrite an analyst can cause - no state transition, no
     * verdict - and the tuple relocates all the same.
     */
    @Test
    void fraudAlertQueueStaysInIdOrderWhenAnAlertIsRewritten() {
        OrderingFixture fixture = seedAccountWithTransfers(5);

        // One alert per transfer: fraud_alerts_one_per_transfer refuses a second on the same one.
        List<Integer> alertIds = new ArrayList<>();
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            for (int transferId : fixture.transferIds()) {
                int alertId = infra.alerts.nextId();
                infra.alerts.add(new FraudAlert(alertId, transferId, "Seeded for the queue"));
                alertIds.add(alertId);
            }
            scope.uow().commit();
        }

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            FraudAlert first = infra.alerts.byId(alertIds.get(0))
                    .orElseThrow(() -> new AssertionError("Seeded alert must exist"));
            first.assignTo("anna.analyst");
            infra.alerts.save(first);
            scope.uow().commit();
        }

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            List<Integer> ids = infra.alerts.all().stream().map(FraudAlert::id).toList();

            assertEquals(
                    alertIds.stream().sorted().toList(),
                    ids,
                    "all() must answer in ascending id order, not in whatever order the heap holds"
                            + " once an alert has been rewritten"
            );

            scope.uow().commit();
        }
    }

// -------------------------------------------------------------------------
// 13) A constraint the store enforces is refused as a conflict, not as a failure
// -------------------------------------------------------------------------

    /**
     * fraud_alerts_one_per_transfer is the schema's last line of defence over the one alert
     * creation site that is guarded by a read instead of by a lock: authorizePayment files an
     * alert when byTransferId comes back empty, so two requests can both read empty and both
     * file. The constraint stops the second, and what its caller must not be told is that the
     * bank is broken - nothing is, and the transaction rolled back whole.
     *
     * The version guard on the same statement cannot reach this case first, which is why the
     * type asserted below is the plain conflict and not FraudAlertChangedException, the descendant
     * that guard raises. The second alert carries an id of its own, so ON CONFLICT (id) never fires,
     * the statement stays on its INSERT arm, and the WHERE that guards the DO UPDATE arm is
     * never evaluated: transfer_id is what refuses the row.
     *
     * Only reachable on the SQL backend. The JSON store has no constraints of its own - it holds
     * a list and appends to it - so this rule cannot be pinned anywhere but here.
     */
    @Test
    void aSecondAlertOnOneTransferIsRefusedAsAConflict() {
        OrderingFixture fixture = seedAccountWithTransfers(1);
        int transferId = fixture.transferIds().get(0);

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            infra.alerts.add(new FraudAlert(infra.alerts.nextId(), transferId, "Filed first"));
            scope.uow().commit();
        }

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow = scope.uow();

            int secondAlertId = infra.alerts.nextId();
            infra.alerts.add(new FraudAlert(
                    secondAlertId, transferId, "Filed by a request that read the queue as empty"));

            ConflictException refused = assertThrows(ConflictException.class, uow::commit,
                    "A write the store refuses over a UNIQUE constraint must surface as a"
                            + " conflict, not as the bare RuntimeException the advice answers 500");

            assertFalse(
                    refused instanceof OptimisticLockException,
                    "The row was refused for duplicating a transfer, not for a stale version: "
                            + refused.getClass().getSimpleName()
            );
            assertTrue(
                    refused.getMessage().contains(String.valueOf(secondAlertId)),
                    "The refusal must name the alert it could not store: " + refused.getMessage()
            );
            assertFalse(
                    refused.getMessage().contains("fraud_alerts_one_per_transfer"),
                    "The constraint name belongs to the driver's exception, not to a message the"
                            + " console prints to its operator: " + refused.getMessage()
            );
        }

        // The commit failed whole, so the alert the first request filed is still the only one and
        // the analyst's queue is what it was.
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            assertEquals(
                    1,
                    infra.alerts.all().size(),
                    "The refused commit must not have left a second alert on the transfer"
            );
            scope.uow().commit();
        }
    }

// -------------------------------------------------------------------------
// 14) What a settled payment owes the payment network survives the round trip
// -------------------------------------------------------------------------

    /** An IBAN no account in these fixtures holds, so a payment to it leaves the bank. */
    private static final String OUTSIDE_THE_BANK = "CZ2001000000000012345678";

    /** A payer who owns an account, and one payee inside the bank to settle against. */
    private record DispatchFixture(int payerId, int payerAccount, IBAN payeeIban) { }

    private DispatchFixture seedPayerAndInBankPayee() {
        final IBAN payerIban = new IBAN("CZ6508000000192000145399");
        final IBAN payeeIban = new IBAN("CZ4308000000192000145407");

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            int payerId = infra.customers.nextId();
            Customer payer = new Customer(payerId, "Dispatch Payer", "dispatch-payer@example.com",
                    new Address("Street 1", "City"), Money.czk(20_000));
            infra.customers.save(payer);
            int payerAccount = infra.accounts.nextId();
            infra.accounts.save(new Account(payerAccount, payerIban, Money.czk(50_000)));
            payer.addAccountId(payerAccount);
            // The second save is what writes accounts.customer_id; see DemoScenario.create.
            infra.customers.save(payer);

            int payeeId = infra.customers.nextId();
            Customer payee = new Customer(payeeId, "Dispatch Payee", "dispatch-payee@example.com",
                    new Address("Street 2", "City"), Money.czk(20_000));
            infra.customers.save(payee);
            int payeeAccount = infra.accounts.nextId();
            infra.accounts.save(new Account(payeeAccount, payeeIban, Money.czk(50_000)));
            payee.addAccountId(payeeAccount);
            infra.customers.save(payee);

            scope.uow().commit();
            return new DispatchFixture(payerId, payerAccount, payeeIban);
        }
    }

    /**
     * A payment that leaves the bank comes back owing the network a dispatch; one that stays
     * inside comes back owing nothing.
     *
     * The two halves have to be asserted together, because either one alone passes under a rule
     * that is wrong in the other direction: a settlement that recorded the intent unconditionally
     * would put every intra-bank payment in front of a gateway that must never see it, and one
     * that recorded nothing would leave the sweep with nothing to find. Only the SQL backend can
     * show the round trip through a real column - what is written here is read back out of
     * PostgreSQL by a later unit of work, which is the half a mapper test cannot reach.
     *
     * Both amounts stay under RuleBasedRiskService's untrusted threshold and their total under the
     * soft daily tier, so both settle immediately rather than parking at WAITING_AUTH - the same
     * arrangement anInBankTransferCreditsTheDestinationInOneSqlTransaction relies on.
     */
    @Test
    void anExternalSettlementOwesADispatchAndAnInBankOneDoesNot() {
        BootstrapServices services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts, infra.uowFactory);
        DispatchFixture fixture = seedPayerAndInBankPayee();

        int leavesTheBank = services.transferService.submitPaymentToIban(
                fixture.payerId(), fixture.payerAccount(), OUTSIDE_THE_BANK, 100.0, "out").transferId();
        int staysInside = services.transferService.submitPaymentToIban(
                fixture.payerId(), fixture.payerAccount(), fixture.payeeIban().value(), 200.0, "in")
                .transferId();

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            Transfer external = infra.transfers.byId(leavesTheBank)
                    .orElseThrow(() -> new AssertionError("The external payment must be persisted"));
            assertEquals(TransferStatus.SENT, external.status(),
                    "this case says nothing unless the payment actually settled");
            assertEquals(
                    DispatchState.PENDING,
                    external.dispatchState(),
                    "a settled payment that left the bank must come back owing the network a dispatch"
            );

            Transfer inBank = infra.transfers.byId(staysInside)
                    .orElseThrow(() -> new AssertionError("The in-bank payment must be persisted"));
            assertEquals(TransferStatus.SENT, inBank.status());
            assertNull(
                    inBank.dispatchState(),
                    "an in-bank payment credits its destination in the same transaction and owes no"
                            + " gateway anything, so it must carry no dispatch state at all"
            );

            scope.uow().commit();
        }
    }

    /**
     * The lookup the startup sweep will run answers with the payments that still owe the network
     * and with nothing else.
     *
     * Four rows, and each of the three that must not appear excludes a different wrong predicate:
     * the in-bank settlement excludes "every SENT transfer", the unsettled one excludes "every
     * transfer", and the one already marked excludes "every transfer that ever owed a dispatch".
     * Marking is asserted through a reload rather than on the instance that was marked, so a mark
     * that never reached the column would fail here instead of passing on an object nobody stored.
     */
    @Test
    void onlyPaymentsThatStillOweTheNetworkAwaitDispatch() {
        BootstrapServices services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts, infra.uowFactory);
        DispatchFixture fixture = seedPayerAndInBankPayee();

        int stillOwed = services.transferService.submitPaymentToIban(
                fixture.payerId(), fixture.payerAccount(), OUTSIDE_THE_BANK, 100.0, "still owed")
                .transferId();
        int alreadyHandedOver = services.transferService.submitPaymentToIban(
                fixture.payerId(), fixture.payerAccount(), OUTSIDE_THE_BANK, 200.0, "handed over")
                .transferId();
        services.transferService.submitPaymentToIban(
                fixture.payerId(), fixture.payerAccount(), fixture.payeeIban().value(), 100.0, "in bank");

        // A transfer that has not settled: nothing has been debited, so no gateway is owed it.
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            infra.transfers.add(new Transfer(infra.transfers.nextId(), fixture.payerAccount(),
                    null, "CZ0401000000000000000000", Money.czk(300)));
            scope.uow().commit();
        }

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            Transfer dispatched = infra.transfers.byId(alreadyHandedOver)
                    .orElseThrow(() -> new AssertionError("The dispatched payment must be persisted"));
            dispatched.markDispatched();
            infra.transfers.save(dispatched);
            scope.uow().commit();
        }

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            assertEquals(
                    List.of(stillOwed),
                    infra.transfers.awaitingDispatch().stream().map(Transfer::id).toList(),
                    "the sweep must see the one payment the network has not been handed, and no other"
            );
            assertEquals(
                    DispatchState.DISPATCHED,
                    infra.transfers.byId(alreadyHandedOver).orElseThrow().dispatchState(),
                    "and the mark must have reached the column, rather than the row having merely"
                            + " fallen out of the lookup"
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
