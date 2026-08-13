// src/test/java/cz/vsb/minibank/uow/MinibankLazyLoadTests.java
package cz.vsb.minibank.uow;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.lazy.LazyRef;
import cz.vsb.minibank.domain.lazy.LazyList;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.json.dto.JsonCustomer;
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
                    Money.czk(1_000));

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

    // ----------------------------------------------------------------------
    // 3) WHAT A DEFERRED LOADER READS LATE, AND WHAT IT MUST NOT
    // ----------------------------------------------------------------------

    /**
     * The customer, loaded through a unit of work that has already ended by the time the caller
     * dereferences anything on it. That is the state a lazy list has to survive, and it is where
     * the store can move on underneath the object.
     */
    private Customer customerFromAnEndedUnitOfWork() {
        UnitOfWork uow = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            Customer c = infra.customers.byId(customerId)
                    .orElseThrow(() -> new AssertionError("Customer must exist"));
            uow.commit();
            return c;
        }
    }

    /**
     * A second stored account that no customer claims yet, so that a live read has something to
     * pick up and a captured one has something to leave out.
     */
    private int openUnclaimedAccount() {
        int id = infra.accounts.nextId();
        infra.accounts.save(new Account(
                id,
                new IBAN("CZ4308000000192000145407"),
                Money.czk(2_000),
                Money.czk(1_000)));
        return id;
    }

    /**
     * A customer answers with the accounts its stored row named at the moment the customer was
     * built, even when that row's id list changes underneath it afterwards.
     *
     * The loader used to read {@code JsonCustomer.accountIds} at dereference time, through a
     * reference to a DTO that is not stable: a save puts a freshly built JsonCustomer into the
     * bundle in place of the old one, so a customer loaded earlier holds an instance the store no
     * longer keeps, and its membership was answered by whichever generation happened to be on the
     * other end of that reference while the accounts themselves came from the current bundle.
     *
     * The append below is what separates the two readings, because it edits the row the closure
     * still points at instead of replacing it. It is written straight into the bundle rather than
     * through a repository because no writer works that way today - which is a fact about this
     * version's writers and not a property of the loader, and is exactly the reason the loader has
     * to hold its own copy of the answer.
     *
     * The balance moved afterwards is the other half of the same rule: identity is captured, state
     * is not. A customer that froze its Account objects at load time would report a balance that is
     * simply out of date, which is the opposite defect and no better.
     */
    @Test
    void customerAccounts_answerTheMembershipCapturedWhenTheCustomerWasBuilt() {
        Customer loadedEarly = customerFromAnEndedUnitOfWork();
        int secondAccountId = openUnclaimedAccount();

        // The stored row claims it, by amendment rather than by replacement.
        infra.store.lock();
        try {
            JsonCustomer row = infra.store.data().customers.stream()
                    .filter(x -> x.id == customerId)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Stored customer row must exist"));
            row.accountIds.add(secondAccountId);
        } finally {
            infra.store.unlock();
        }

        Account moved = infra.accounts.byId(accountId)
                .orElseThrow(() -> new AssertionError("Account must exist"));
        moved.credit(Money.czk(500));
        infra.accounts.save(moved);

        List<Account> accounts = loadedEarly.accounts();

        assertEquals(
                List.of(accountId),
                accounts.stream().map(Account::id).toList(),
                "accounts() must answer the ids the row carried when the customer was built"
        );
        assertEquals(
                Money.czk(10_500),
                accounts.get(0).balance(),
                "the accounts themselves stay lazy, so their state is read when accounts() is called"
        );
    }

    /**
     * The same rule against the writer that exists: saving a customer replaces its stored row, and
     * a customer object built before that save keeps answering with the accounts the row named
     * then.
     *
     * Nothing about this answer is new. A replaced row leaves the detached instance alone, so the
     * old loader and the new one agree here - they agree by accident, and the accident is that no
     * writer amends a row in place. What is pinned is which of the two answers is the intended one,
     * so that a customer holding a captured list cannot later be read as a regression against the
     * store having moved on.
     */
    @Test
    void customerAccounts_areUnmovedByTheStoredRowBeingReplaced() {
        Customer loadedEarly = customerFromAnEndedUnitOfWork();
        int secondAccountId = openUnclaimedAccount();

        UnitOfWork later = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(later)) {
            Customer reloaded = infra.customers.byId(customerId)
                    .orElseThrow(() -> new AssertionError("Customer must exist"));
            reloaded.addAccountId(secondAccountId);
            infra.customers.save(reloaded);
            later.commit();
        }

        assertEquals(
                2,
                infra.accounts.byCustomerId(customerId).size(),
                "the store must hold both accounts by now, or the assertion below proves nothing"
        );

        assertEquals(
                List.of(accountId),
                loadedEarly.accounts().stream().map(Account::id).toList(),
                "a customer built before the save answers with the accounts its row named then"
        );
    }
}
