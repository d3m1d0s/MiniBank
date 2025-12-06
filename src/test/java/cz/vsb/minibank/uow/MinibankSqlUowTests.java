package cz.vsb.minibank.uow;

import cz.vsb.minibank.domain.*;
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
 *
 * Requires running PostgreSQL instance with schema.sql applied.
 *
 * Example Docker command:
 *
 *   docker run --name minibank-pg ^
 *     -e POSTGRES_USER=minibank ^
 *     -e POSTGRES_PASSWORD=minibank ^
 *     -e POSTGRES_DB=minibank ^
 *     -p 5432:5432 ^
 *     -d postgres:16
 *
 * And then apply schema.sql to database "minibank".
 */
public class MinibankSqlUowTests {

    private String jdbcUrl;
    private String dbUser;
    private String dbPass;

    private Bootstrap infra;

    @BeforeEach
    void setUp() throws Exception {
        // Allow overriding via system properties, fallback to Docker defaults
        jdbcUrl = System.getProperty("minibank.jdbcUrl", "jdbc:postgresql://localhost:5432/minibank");
        dbUser  = System.getProperty("minibank.dbUser",  "minibank");
        dbPass  = System.getProperty("minibank.dbPass",  "minibank");

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
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }

        // New UoW: load persisted data from DB
        UnitOfWork uow2 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow2)) {
            Account reloaded = infra.accounts.byId(accountId).orElseThrow();
            assertEquals(accountId, reloaded.id());
            assertEquals("CZ6508000000192000145399", reloaded.iban().value());
            assertEquals(0, Money.czk(20_000).amount().compareTo(reloaded.balance().amount()));
            uow2.commit();
        } catch (RuntimeException e) {
            uow2.rollback();
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // 2) Customer + Beneficiary: aggregate update + persistence
    // -------------------------------------------------------------------------

    @Test
    void customerBeneficiaries_areUpdatedInAggregateAndPersisted() {
        int customerId;

        // First UoW: create a customer without beneficiaries
        UnitOfWork uow1 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow1)) {
            customerId = infra.customers.nextId();
            Customer c = new Customer(
                    customerId,
                    "Lazy SQL User",
                    "lazy-sql@example.com",
                    new Address("Street 1", "City")
            );
            infra.customers.save(c);
            uow1.commit();
        } catch (RuntimeException e) {
            uow1.rollback();
            throw e;
        }

        // Second UoW: load customer, add beneficiary via repository, commit
        UnitOfWork uow2 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow2)) {
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
                    new IBAN("CZ0201000000000098765432"),
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
        } catch (RuntimeException e) {
            uow2.rollback();
            throw e;
        }

        // Third UoW: verify persistence in DB
        UnitOfWork uow3 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow3)) {
            Customer reloaded = infra.customers.byId(customerId)
                    .orElseThrow(() -> new AssertionError("Customer must exist after commit"));

            assertEquals(
                    1,
                    reloaded.beneficiaries().size(),
                    "After commit, beneficiary must be persisted and loaded from DB"
            );
            Beneficiary b2 = reloaded.beneficiaries().get(0);
            assertEquals("Alice SQL", b2.name());
            assertEquals("CZ0201000000000098765432", b2.iban().value());

            uow3.commit();
        } catch (RuntimeException e) {
            uow3.rollback();
            throw e;
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
        UnitOfWork uow1 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow1)) {
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

            uow1.commit();
        } catch (RuntimeException e) {
            uow1.rollback();
            throw e;
        }

        // UoW #2: create transfer and check IdentityMap via byId() twice
        UnitOfWork uow2 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow2)) {
            TransferRepository transfers = infra.transfers;

            transferId = transfers.nextId();
            Transfer t = new Transfer(
                    transferId,
                    accountId,
                    null,
                    "CZ0201000000000000000000",
                    Money.czk(1_000),
                    "CZK"
            );

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

            uow2.commit();
        } catch (RuntimeException e) {
            uow2.rollback();
            throw e;
        }

        // UoW #3: after commit, verify persistence and IdentityMap across byId + bySourceAccount
        UnitOfWork uow3 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow3)) {
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

            uow3.commit();
        } catch (RuntimeException e) {
            uow3.rollback();
            throw e;
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
        UnitOfWork uow1 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow1)) {
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
                    "CZ0201000000000000000000",
                    Money.czk(500),
                    "CZK"
            );
            infra.transfers.add(t);

            uow1.commit();
        } catch (RuntimeException e) {
            uow1.rollback();
            throw e;
        }

        // UoW #2: create FraudAlert with metadata and test IdentityMap via byId() twice
        UnitOfWork uow2 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow2)) {
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

            uow2.commit();
        } catch (RuntimeException e) {
            uow2.rollback();
            throw e;
        }

        // UoW #3: after commit, verify persistence + IdentityMap across byId() and byTransferId()
        UnitOfWork uow3 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow3)) {
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

            uow3.commit();
        } catch (RuntimeException e) {
            uow3.rollback();
            throw e;
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
        UnitOfWork uow1 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow1)) {
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
        } catch (RuntimeException e) {
            uow1.rollback();
            throw e;
        }

        // UoW #2: first load byId, then byCustomerId -> must reuse same instance from IdentityMap
        UnitOfWork uow2 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow2)) {
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
        } catch (RuntimeException e) {
            uow2.rollback();
            throw e;
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
        UnitOfWork uow1 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow1)) {
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

            // explicit rollback: nothing from this UoW should hit the database
            uow1.rollback();
        } catch (RuntimeException e) {
            // rollback already called, just rethrow to see the failure
            throw e;
        }

        // UoW #2: verify that there is no such customer/account in DB
        UnitOfWork uow2 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow2)) {
            assertTrue(
                    infra.customers.byId(customerId).isEmpty(),
                    "Customer must not be persisted if UoW was rolled back"
            );

            assertTrue(
                    infra.accounts.byId(accountId).isEmpty(),
                    "Account must not be persisted if UoW was rolled back"
            );

            uow2.commit();
        } catch (RuntimeException e) {
            uow2.rollback();
            throw e;
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
        UnitOfWork uow1 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow1)) {
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

            uow1.commit();
        } catch (RuntimeException e) {
            uow1.rollback();
            throw e;
        }

        // UoW #2: reload account and compare monetary values
        UnitOfWork uow2 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow2)) {
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
        } catch (RuntimeException e) {
            uow2.rollback();
            throw e;
        }
    }

}
