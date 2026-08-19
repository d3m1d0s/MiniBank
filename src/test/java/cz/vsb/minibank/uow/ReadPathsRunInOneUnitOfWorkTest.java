package cz.vsb.minibank.uow;

import cz.vsb.minibank.api.AuthorizationController;
import cz.vsb.minibank.api.FraudController;
import cz.vsb.minibank.application.OwnershipGuard;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.SimpleFeePolicy;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every repository call a read endpoint makes happens inside one unit of work.
 *
 * This is the property that fixes the cost. There is no connection pool in this project, so a
 * repository call made with no unit of work in scope opens a JDBC connection and tears it down
 * again; the analyst queue asked for every alert and then for one transfer per alert, so drawing
 * one screen cost N+1 connections, and the customer's waiting list did the same per account.
 *
 * Asserted on the unit of work rather than by counting connections in the database.
 * pg_stat_database is written by the statistics collector with a delay of up to half a second,
 * so a count taken straight after the call under test is not a measurement, it is a race - one
 * that passes and fails on timing rather than on behaviour. What the connection count was ever
 * standing in for is this: was a unit of work in scope, and was it the same one throughout.
 */
class ReadPathsRunInOneUnitOfWorkTest {

    /**
     * Records the unit of work that was in scope for every call made through it.
     *
     * A decorator rather than a mock, because the endpoints have to keep working: the answers
     * come from the real JSON repositories underneath, and only the observation is added.
     */
    private static final class Witness {
        final List<UnitOfWork> seen = new ArrayList<>();

        void record() {
            seen.add(UowContext.current());
        }

        /** The one unit of work every call ran inside, asserting there was exactly one. */
        UnitOfWork theOnlyOne(String what) {
            assertFalse(seen.isEmpty(), what + " made no repository calls at all, so this test"
                    + " would pass for the wrong reason");
            UnitOfWork first = seen.get(0);
            assertNotNull(first, what + " made its first repository call with no unit of work in"
                    + " scope, so that call opened a connection of its own");
            for (int i = 1; i < seen.size(); i++) {
                assertEquals(first, seen.get(i), what + " call " + (i + 1) + " of " + seen.size()
                        + " ran in a different unit of work than the first");
            }
            return first;
        }
    }

    private final Witness witness = new Witness();

    @TempDir
    Path tempDir;

    private Bootstrap infra;
    private FraudController fraudController;
    private AuthorizationController authorizationController;

    private static final IBAN PAYER_IBAN = new IBAN("CZ6508000000192000145399");
    private static final String TARGET_IBAN = "CZ2001000000000012345678";

    /** A second beneficiary, so a page can tell one lookup per beneficiary from one per row. */
    private static final String OTHER_TARGET_IBAN = "CZ4308000000192000145407";

    /** A third, named only by a payment that has already settled and so asks about nobody. */
    private static final String SETTLED_TARGET_IBAN = "CZ9608000000192000145423";

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());

        // All four, including the customers one the guard reads through. Watching three of them
        // left OwnershipGuard.requireCaller unobserved, and that is the lookup every guarded read
        // makes first, so the property below was never asserted about the one repository a
        // controller reaches indirectly.
        AccountRepository accounts = watch(infra.accounts);
        CustomerRepository customers = watch(infra.customers);
        TransferRepository transfers = watch(infra.transfers);
        FraudAlertRepository alerts = watch(infra.alerts);

        fraudController = new FraudController(alerts, transfers, accounts, customers, null,
                new SimpleFeePolicy(), infra.uowFactory);
        authorizationController = new AuthorizationController(null, accounts, transfers,
                new SimpleFeePolicy(), new OwnershipGuard(customers, accounts),
                infra.uowFactory);
    }

    @Test
    void theAlertQueueRunsEveryLookupInOneUnitOfWork() {
        seedAlertedTransfers(3);
        signInAsAnalyst();
        witness.seen.clear();

        fraudController.listAlerts(null, null, null, null, null, null, null, null, null);

        UnitOfWork only = witness.theOnlyOne("The alert queue");
        // Three, and three whatever the queue holds: the page, its total, and the counters. It
        // used to be one call for every alert plus one for the payment behind each, which is what
        // made it expensive; the upper bound is the assertion now, not the lower one.
        assertEquals(3, witness.seen.size(),
                "the queue must cost the same three lookups whatever the queue holds; saw "
                        + witness.seen.size() + " calls");
        assertNotNull(only);
    }

    @Test
    void theAlertDetailRunsEveryLookupInOneUnitOfWork() {
        seedAlertedTransfers(2);
        signInAsAnalyst();
        int alertId = infra.alerts.all().get(0).id();
        witness.seen.clear();

        fraudController.getAlert(alertId);

        witness.theOnlyOne("The alert detail");
    }

    /**
     * Opening an alert costs the same whether the customer behind it has sent two payments or
     * eight.
     *
     * A count would have to be restated every time the panel gains a fact, so the assertion is
     * the comparison instead, and the comparison is the property that matters: the history beside
     * an alert now asks the store which side of the bank each unsettled payment was heading for,
     * and asking that once per row would put a repository call behind every line of a table that
     * exists to be scanned. Both customers here pay one beneficiary, so an answer kept for the
     * page makes the two screens cost the same and an answer fetched per row does not.
     */
    @Test
    void theAlertDetailCostsTheSameWhateverTheCustomersHistoryHolds() {
        seedAlertedTransfers(2);
        int smallAlert = infra.alerts.all().get(0).id();
        signInAsAnalyst();
        witness.seen.clear();
        fraudController.getAlert(smallAlert);
        int forTwoPayments = witness.seen.size();

        seedAlertedTransfers(8);
        int largeAlert = infra.alerts.all().get(infra.alerts.all().size() - 1).id();
        witness.seen.clear();
        fraudController.getAlert(largeAlert);
        int forEightPayments = witness.seen.size();

        assertEquals(forTwoPayments, forEightPayments,
                "the alert detail cost " + forTwoPayments + " lookups for a customer with two"
                        + " payments and " + forEightPayments + " for one with eight, so its"
                        + " price follows the length of the history");
    }

    @Test
    void theCustomersWaitingListRunsEveryLookupInOneUnitOfWork() {
        int customerId = seedAlertedTransfers(2);
        signInAsCustomer(customerId);
        witness.seen.clear();

        authorizationController.listMyWaiting(0, 25);

        UnitOfWork only = witness.theOnlyOne("The waiting list");
        // Three, and three whatever the customer holds: the caller, the total, and the page. It
        // used to be one call for the accounts plus one per account for its transfers, and it
        // then filtered and counted whatever that returned.
        assertEquals(3, witness.seen.size(),
                "the waiting list must cost the same three lookups whatever the customer holds;"
                        + " saw " + witness.seen.size() + " calls");
        assertNotNull(only);
    }

    /**
     * Four fixed lookups, and one more per beneficiary that has to be asked about.
     *
     * The four are the caller through the ownership guard, the customer's accounts so a row can
     * name the one the payment left, the count beside the list, and the page itself. They do not
     * move with the size of the history.
     *
     * The rest is the price of saying whether a payment stayed inside this bank, and it is no
     * longer a constant, which is why this is spelled out rather than counted. A payment that has
     * settled answers from its own record and reaches no repository at all. One that has not is
     * asked about once per beneficiary for the whole page, not once per row, so the five rows and
     * three beneficiaries seeded here cost two lookups and not four. The honest upper bound is
     * therefore a page whose rows have not settled and name a different beneficiary each: that is
     * one lookup per row, bounded by the page size rather than by the history behind it.
     */
    @Test
    void theCustomersPaymentHistoryRunsEveryLookupInOneUnitOfWork() {
        int customerId = seedAlertedTransfers(3);
        seedPayment(customerId, OTHER_TARGET_IBAN, false);
        seedPayment(customerId, SETTLED_TARGET_IBAN, true);
        signInAsCustomer(customerId);
        witness.seen.clear();

        authorizationController.listMyTransfers(0, 25);

        UnitOfWork only = witness.theOnlyOne("The payment history");
        assertEquals(7, witness.seen.size(),
                "the payment history must cost four fixed lookups - the caller through the"
                        + " ownership guard, their accounts for the numbers, the count and the"
                        + " page - plus one per DISTINCT beneficiary it has to ask about. It asks"
                        + " about a payment whenever the row recorded no obligation to the"
                        + " network, which is every payment here: four have not settled and the"
                        + " fifth settled inside the bank. They name three numbers between them,"
                        + " so the answer is seven. The count is per number and not per row"
                        + " because the caller raises one map for the page; a sixth payment to"
                        + " any of those three would add nothing; saw "
                        + witness.seen.size() + " calls");
        assertNotNull(only);
    }

    /**
     * Three lookups - the caller through {@link OwnershipGuard#requireCaller}, the transfer, then
     * its account - which have to be three uses of one unit of work rather than three connections.
     *
     * The two customer lists above resolve their caller through the same guard, so
     * CustomerRepository is exercised by them as well; this is the only read that then names a
     * transfer and has to prove the ownership check and the lookups behind it share the one
     * transaction.
     */
    @Test
    void theTransferDetailRunsEveryLookupInOneUnitOfWork() {
        int customerId = seedAlertedTransfers(1);
        signInAsCustomer(customerId);
        int transferId = infra.alerts.all().get(0).transferId();
        witness.seen.clear();

        authorizationController.transferDetails(transferId);

        witness.theOnlyOne("The transfer detail");
        assertTrue(witness.seen.size() >= 3,
                "it must have loaded the caller, the transfer and the account behind it; saw "
                        + witness.seen.size() + " calls");
    }

    @Test
    void aReadLeavesNoUnitOfWorkBehindForTheNextRequest() {
        seedAlertedTransfers(1);
        signInAsAnalyst();

        fraudController.listAlerts(null, null, null, null, null, null, null, null, null);

        // The JSON unit of work holds the store lock until it completes, so one left open would
        // wedge every later request rather than merely leaking a connection.
        assertEquals(null, UowContext.current(),
                "the read must close its unit of work on the way out");
    }

    // ------------------------------------------------------------------
    // fixture and plumbing
    // ------------------------------------------------------------------

    private void signInAsAnalyst() {
        SecurityContext.setCurrentUser(new User(2, "anna.analyst", new byte[]{1}, new byte[]{2},
                UserRole.FRAUD_ANALYST, null));
    }

    private void signInAsCustomer(int customerId) {
        SecurityContext.setCurrentUser(new User(1, "alice", new byte[]{1}, new byte[]{2},
                UserRole.CUSTOMER, customerId));
    }

    /**
     * Builds a customer with one account and the given number of alerted transfers, straight
     * through the repositories: what this test observes is the read path, not how a row got there.
     */
    private int seedAlertedTransfers(int howMany) {
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            int customerId = infra.customers.nextId();
            Customer c = new Customer(customerId, "Queue Probe", "queue@example.com",
                    new Address("Hlavni 1", "Ostrava"));
            infra.customers.save(c);

            int accountId = infra.accounts.nextId();
            infra.accounts.save(new Account(accountId, PAYER_IBAN,
                    Money.czk(5_000_000), Money.czk(4_000_000), null));
            c.addAccountId(accountId);
            infra.customers.save(c);

            for (int i = 0; i < howMany; i++) {
                Transfer t = new Transfer(infra.transfers.nextId(), accountId, null,
                        TARGET_IBAN, Money.czk(12_000));
                t.holdForReview(null);
                infra.transfers.add(t);
                infra.alerts.add(new FraudAlert(infra.alerts.nextId(), t.id(),
                        "New beneficiary + high amount"));
            }

            scope.uow().commit();
            return customerId;
        }
    }

    /**
     * One more payment from the first account a customer already holds.
     *
     * A settled one is hydrated rather than sent, because what this class observes is the read
     * path: the row it needs is one that has been sent and owes the network nothing, and going
     * through {@code Transfer.send} to get there would drag two account rows and a fee policy
     * into a test about how many times a repository is called.
     */
    private void seedPayment(int customerId, String targetIban, boolean settled) {
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            int accountId = infra.accounts.byCustomerId(customerId).get(0).id();

            Transfer t = new Transfer(infra.transfers.nextId(), accountId, null, targetIban,
                    Money.czk(12_000));
            if (settled) {
                t.hydrateForLoad(TransferStatus.SENT, null, null, t.createdAt());
                t.hydrateSettlement(Money.czk(50), t.createdAt());
            } else {
                t.holdForReview(null);
            }
            infra.transfers.add(t);

            scope.uow().commit();
        }
    }

    private AccountRepository watch(AccountRepository real) {
        return (AccountRepository) java.lang.reflect.Proxy.newProxyInstance(
                AccountRepository.class.getClassLoader(),
                new Class<?>[]{AccountRepository.class},
                (proxy, method, args) -> {
                    witness.record();
                    return invoke(real, method, args);
                });
    }

    private CustomerRepository watch(CustomerRepository real) {
        return (CustomerRepository) java.lang.reflect.Proxy.newProxyInstance(
                CustomerRepository.class.getClassLoader(),
                new Class<?>[]{CustomerRepository.class},
                (proxy, method, args) -> {
                    witness.record();
                    return invoke(real, method, args);
                });
    }

    private TransferRepository watch(TransferRepository real) {
        return (TransferRepository) java.lang.reflect.Proxy.newProxyInstance(
                TransferRepository.class.getClassLoader(),
                new Class<?>[]{TransferRepository.class},
                (proxy, method, args) -> {
                    witness.record();
                    return invoke(real, method, args);
                });
    }

    private FraudAlertRepository watch(FraudAlertRepository real) {
        return (FraudAlertRepository) java.lang.reflect.Proxy.newProxyInstance(
                FraudAlertRepository.class.getClassLoader(),
                new Class<?>[]{FraudAlertRepository.class},
                (proxy, method, args) -> {
                    witness.record();
                    return invoke(real, method, args);
                });
    }

    /** Unwraps the reflection layer so a domain exception arrives as itself. */
    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
