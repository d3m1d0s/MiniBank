// src/test/java/cz/vsb/minibank/uow/MinibankLazyLoadTests.java
package cz.vsb.minibank.uow;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.lazy.LazyRef;
import cz.vsb.minibank.domain.lazy.LazyList;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class MinibankLazyLoadTests {

    Path tempDir;
    String dataPath;
    Bootstrap infra;

    // use the same ids across all tests
    int customerId;
    int accountId;
    int transferId;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("minibank-lazy-");
        dataPath = tempDir.resolve("data.json").toString();
        infra = new Bootstrap(dataPath);

        // 1) create a customer
        customerId = infra.customers.nextId();
        Customer c = new Customer(
                customerId,
                "Lazy User",
                "lazy@example.com",
                new Address("Street 1", "City")
        );
        infra.customers.save(c);

        // 2) create an account
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

        // 3) create a single transfer with this account as source (through UoW)
        UnitOfWorkFactory uowFactory = infra.uowFactory;
        try (UowScope scope = new UowScope(uowFactory.begin())) {
            UnitOfWork uow = scope.uow();
            transferId = infra.transfers.nextId();

            // If your Transfer constructor differs, adjust parameters accordingly
            Transfer t = new Transfer(
                    transferId,
                    accountId,
                    null,
                    "CZ0401000000000000000000",
                    Money.czk(1_000),
                    "CZK"
            );

            infra.transfers.add(t);
            uow.commit();
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null) {
            // clean up temporary directory
            Files.walk(tempDir)
                    .sorted((p1, p2) -> p2.compareTo(p1))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    // ----------------------------------------------------------------------
    // 1) UNIT TESTS FOR LAZYREF / LAZYLIST (pure laziness)
    // ----------------------------------------------------------------------

    @Test
    void lazyRef_callsLoaderOnlyOnce() {
        AtomicInteger counter = new AtomicInteger();

        LazyRef<String> ref = new LazyRef<>(() -> {
            counter.incrementAndGet();
            return "value";
        });

        // loader must not be called before first get()
        assertFalse(ref.isLoaded(), "LazyRef must not be loaded before first get()");
        assertEquals(0, counter.get(), "Loader must not be invoked before first get()");

        String v1 = ref.get();
        assertTrue(ref.isLoaded(), "After first get() LazyRef must be marked as loaded");
        assertEquals("value", v1);
        assertEquals(1, counter.get(), "Loader must be called exactly once");

        String v2 = ref.get();
        assertSame(v1, v2, "Subsequent get() must return the same instance");
        assertEquals(1, counter.get(), "Subsequent get() must not invoke loader again");
    }

    @Test
    void lazyList_callsLoaderOnlyOnce() {
        AtomicInteger counter = new AtomicInteger();

        LazyList<Integer> list = new LazyList<>(() -> {
            counter.incrementAndGet();
            return List.of(1, 2, 3);
        });

        assertFalse(list.isLoaded(), "LazyList must not be loaded before first getAll()");
        assertEquals(0, counter.get(), "Loader must not be invoked before first getAll()");

        List<Integer> l1 = list.getAll();
        assertTrue(list.isLoaded(), "After first getAll() LazyList must be marked as loaded");
        assertEquals(List.of(1, 2, 3), l1);
        assertEquals(1, counter.get(), "Loader must be called exactly once");

        List<Integer> l2 = list.getAll();
        assertSame(l1, l2, "Subsequent getAll() must return the same list");
        assertEquals(1, counter.get(), "Subsequent getAll() must not invoke loader again");
    }

    // ----------------------------------------------------------------------
    // 2) INTEGRATION TESTS: TRANSFER + CUSTOMER via JSON + UoW
    // ----------------------------------------------------------------------

    @Test
    void transferSourceAccount_isLazyAndUsesIdentityMap() {
        UnitOfWorkFactory uowFactory = infra.uowFactory;
        UnitOfWork uow = uowFactory.begin();

        try (UowScope __ = new UowScope(uow)) {
            Transfer t = infra.transfers.byId(transferId)
                    .orElseThrow(() -> new AssertionError("Transfer must exist"));

            // 1) lazily load the account
            Account fromLazy = t.sourceAccount();
            assertNotNull(fromLazy, "Lazy-loaded source account must not be null");
            assertEquals(accountId, fromLazy.id(), "sourceAccount() must return account with expected id");

            // 2) same UoW: repository must return the same instance from IdentityMap
            Account fromRepo = infra.accounts.byId(accountId)
                    .orElseThrow(() -> new AssertionError("Account must exist"));

            assertSame(
                    fromLazy,
                    fromRepo,
                    "Within a single UoW lazy-loaded Account and Account from repository must be the same instance"
            );

            uow.commit();
        }
    }

    @Test
    void customerAccounts_lazyListUsesIdentityMap() {
        UnitOfWorkFactory uowFactory = infra.uowFactory;
        UnitOfWork uow = uowFactory.begin();

        try (UowScope __ = new UowScope(uow)) {
            Customer c = infra.customers.byId(customerId)
                    .orElseThrow(() -> new AssertionError("Customer must exist"));

            // 1) trigger LazyList<Account> in Customer
            List<Account> lazyAccounts = c.accounts();
            assertFalse(lazyAccounts.isEmpty(), "Lazy-loaded accounts must not be empty");
            assertEquals(accountId, lazyAccounts.get(0).id());

            // 2) same UoW: byCustomerId must return the same Account instance from IdentityMap
            List<Account> fromRepo = infra.accounts.byCustomerId(customerId);
            assertFalse(fromRepo.isEmpty(), "byCustomerId must return at least one account");
            assertEquals(accountId, fromRepo.get(0).id());

            assertSame(
                    lazyAccounts.get(0),
                    fromRepo.get(0),
                    "Account from LazyList and Account from repository must be the same instance within the UoW"
            );

            uow.commit();
        }
    }
}
