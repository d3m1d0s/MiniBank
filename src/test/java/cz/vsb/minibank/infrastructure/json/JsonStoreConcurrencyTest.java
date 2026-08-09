package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FeePolicy;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.SimpleFeePolicy;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the JSON adapter from several threads at once and checks the store
 * afterwards.
 *
 * Every case releases its threads from a CyclicBarrier, so they enter the application
 * service together instead of drifting past each other. Against the pre-fix adapter -
 * shared ArrayLists, a synchronized data() that protected only the field read, and a
 * synchronized commit() on a monitor that was fresh per request - this produced lost
 * updates, dropped list elements, alerts pointing at transfers that were never persisted,
 * and ConcurrentModificationException out of reads.
 *
 * The assertions are on the persisted store, read back through a second Bootstrap over the
 * same file, not on anything a controller returned. What a controller reports after its
 * transaction has committed is a separate question: it re-reads outside the unit of work
 * and can pick up a later transaction's numbers. That is documented, and it is not what
 * this test is about.
 */
class JsonStoreConcurrencyTest {

    /** Enough threads to collide reliably, few enough to stay fast. */
    private static final int THREADS = 10;

    private static final Money PAYMENT = Money.czk(100.00);
    private static final Money FLAGGED_PAYMENT = Money.czk(12_000.00);
    private static final String TARGET_IBAN = "CZ2108000000192000145415";

    /** No thread should ever wait this long; a timeout means something is wedged. */
    private static final int TIMEOUT_SECONDS = 60;

    @TempDir
    Path tempDir;

    private Path dataFile;
    private Bootstrap infra;
    private BootstrapServices services;
    private final FeePolicy feePolicy = new SimpleFeePolicy();

    private int customerId;
    private int accountId;

    @BeforeEach
    void setUp() {
        dataFile = tempDir.resolve("data.json");
        infra = new Bootstrap(dataFile.toString());
        services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts, infra.uowFactory);
    }

    @AfterEach
    void tearDown() {
        // Nothing to close; @TempDir removes the file.
    }

    /**
     * Ten concurrent 100.00 payments against a 5000.00 balance. Each one is below every
     * risk threshold, so each settles immediately and debits the account.
     *
     * This is the reported lost update: before the fix the ten threads each read 5000.00
     * and wrote their own debited figure back, so the balance landed near 4900.00 with
     * most of the work discarded.
     */
    @Test
    void concurrentPaymentsEachDebitTheAccountExactlyOnce() throws Exception {
        seedAccount(Money.czk(5_000.00), Money.czk(1_000_000.00));

        List<Integer> transferIds = runConcurrently(
                () -> services.transferService.submitPaymentToIban(
                        customerId, accountId, TARGET_IBAN, PAYMENT.amount().doubleValue(), "").transferId());

        Bootstrap reopened = new Bootstrap(dataFile.toString());
        List<Transfer> persisted = reopened.transfers.bySourceAccount(accountId);
        Account account = reopened.accounts.byId(accountId).orElseThrow();

        assertEquals(THREADS, distinct(transferIds).size(),
                "Each payment must get its own transfer id");
        assertEquals(THREADS, persisted.size(),
                "Every committed transfer must be on disk, none dropped by a racing ArrayList.add");
        assertEquals(distinct(transferIds), idsOf(persisted),
                "The persisted transfers must be exactly the ones the service reported");

        List<Transfer> sent = persisted.stream()
                .filter(t -> t.status() == TransferStatus.SENT)
                .toList();
        assertEquals(THREADS, sent.size(), "Every payment below the risk thresholds settles");

        Money expected = Money.czk(5_000.00);
        for (Transfer t : sent) {
            expected = expected.minus(t.amount().plus(t.feeAmount(feePolicy)));
        }
        assertEquals(expected, account.balance(),
                "The balance must be the opening balance minus every transfer reported SENT");
        assertEquals(Money.czk(4_000.00), account.balance(),
                "Ten 100.00 payments with no fee move 5000.00 to 4000.00");

        // What stood here asserted the same fact against Account.transferIds, which is gone.
        // The fact itself is still asserted twice, at lines 111 and 113 above -
        // persisted.size() and idsOf(persisted) - against Bundle.transfers, the top-level list
        // every payment commit appends to, which is where the dropped-element race actually
        // lived.
        //
        // What is genuinely lost, written down rather than quietly absorbed: transferIds was
        // the only list nested inside a Bundle DTO that any concurrent path wrote, so no test
        // now exercises a nested collection under concurrent commits. The store lock does not
        // distinguish the two - one lock guards the whole Bundle and everything reachable from
        // it, so a nested list was never protected differently from a top-level one - but if a
        // concurrent path ever starts appending to accountIds, beneficiaries or tags, a canary
        // of this shape belongs back here.
    }

    /**
     * Ten concurrent 12 000.00 payments to an untrusted IBAN. Each one trips the fraud
     * rules, so each writes a HELD_FOR_REVIEW transfer and a fraud alert in the same
     * transaction - two structural list additions per commit.
     *
     * Before the fix these two adds could race independently, which is how an alert ended
     * up referencing a transfer id that was not on disk. Nothing is debited on this path,
     * so the balance must not move at all.
     */
    @Test
    void concurrentFlaggedPaymentsNeverLeaveAnAlertWithoutItsTransfer() throws Exception {
        Money opening = Money.czk(500_000.00);
        seedAccount(opening, Money.czk(1_000_000.00));

        List<Integer> transferIds = runConcurrently(
                () -> services.transferService.submitPaymentToIban(
                        customerId, accountId, TARGET_IBAN, FLAGGED_PAYMENT.amount().doubleValue(), "").transferId());

        Bootstrap reopened = new Bootstrap(dataFile.toString());
        List<Transfer> persisted = reopened.transfers.bySourceAccount(accountId);
        List<FraudAlert> alerts = reopened.alerts.all();
        Account account = reopened.accounts.byId(accountId).orElseThrow();

        assertEquals(THREADS, persisted.size(), "Every flagged transfer must be on disk");
        assertEquals(THREADS, alerts.size(), "Every flagged transfer must have raised one alert");

        Set<Integer> persistedIds = idsOf(persisted);
        for (FraudAlert alert : alerts) {
            assertTrue(persistedIds.contains(alert.transferId()),
                    "Fraud alert " + alert.id() + " references transfer " + alert.transferId()
                            + ", which is not in the store: " + persistedIds);
        }
        assertEquals(distinct(transferIds), persistedIds,
                "The persisted transfers must be exactly the ones the service reported");

        assertEquals(opening, account.balance(),
                "A transfer waiting for authorization must not have debited anything");
    }

    /**
     * Writers and readers at the same time. The readers hammer exactly the list-walking
     * paths the controllers use with no unit of work bound, which is where the reported
     * ConcurrentModificationException and the phantom "Transfer not found" came from.
     *
     * The reader threads assert nothing about which rows they see - a read outside a unit
     * of work is a point-in-time snapshot and is allowed to miss an in-flight commit. What
     * they must never do is throw, or observe a torn row.
     */
    @Test
    void concurrentReadsDuringWritesNeitherThrowNorSeeTornRows() throws Exception {
        seedAccount(Money.czk(5_000.00), Money.czk(1_000_000.00));

        int readers = THREADS;
        CyclicBarrier start = new CyclicBarrier(THREADS + readers);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS + readers);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return services.transferService.submitPaymentToIban(
                            customerId, accountId, TARGET_IBAN, PAYMENT.amount().doubleValue(), "").transferId();
                }));
            }
            for (int i = 0; i < readers; i++) {
                futures.add(pool.submit(() -> {
                    start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    for (int pass = 0; pass < 50; pass++) {
                        Customer customer = infra.customers.byId(customerId).orElseThrow();
                        assertTrue(customer.accountIds().contains(accountId));

                        List<Account> accounts = infra.accounts.byCustomerId(customerId);
                        assertEquals(1, accounts.size());

                        for (Transfer t : infra.transfers.bySourceAccount(accountId)) {
                            // A row that is visible must be whole: an id, a status and an
                            // amount, and a source account that resolves.
                            assertTrue(t.id() > 0);
                            assertTrue(t.status() != null);
                            assertTrue(t.amount().amount().signum() > 0);
                            assertEquals(accountId, t.sourceAccount().id());
                        }
                        infra.alerts.all();
                    }
                    return null;
                }));
            }
            awaitAll(futures);
        } finally {
            shutdown(pool);
        }

        Bootstrap reopened = new Bootstrap(dataFile.toString());
        assertEquals(THREADS, reopened.transfers.bySourceAccount(accountId).size(),
                "Reads running alongside the writes must not have cost a single persisted transfer");
        assertEquals(Money.czk(4_000.00), reopened.accounts.byId(accountId).orElseThrow().balance(),
                "Ten 100.00 payments move 5000.00 to 4000.00 whatever else is reading");
    }

    // ------------------------------------------------------------------
    // fixture and plumbing
    // ------------------------------------------------------------------

    private void seedAccount(Money balance, Money dailyLimit) {
        customerId = infra.customers.nextId();
        Customer customer = new Customer(
                customerId, "Concurrency Probe", "probe@example.com", new Address("Hlavni 1", "Ostrava"));
        infra.customers.save(customer);

        accountId = infra.accounts.nextId();
        infra.accounts.save(new Account(
                accountId, new IBAN("CZ6508000000192000145399"), balance, dailyLimit));

        customer.addAccountId(accountId);
        infra.customers.save(customer);
    }

    /**
     * Runs one call on each of THREADS threads, released together from a barrier, and
     * returns their results. Any exception from any thread fails the test with that
     * exception as the cause, which is what catches a ConcurrentModificationException or
     * a phantom "Transfer not found" thrown out of a worker.
     */
    private List<Integer> runConcurrently(Callable<Integer> body) throws Exception {
        CyclicBarrier start = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    // Outside the transaction on purpose: a JSON unit of work holds the
                    // store lock for its whole life, so waiting on a barrier inside one
                    // would deadlock the test rather than test anything.
                    start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return body.call();
                }));
            }
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : futures) {
                results.add(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            shutdown(pool);
        }
    }

    private static void awaitAll(List<Future<?>> futures) throws Exception {
        for (Future<?> f : futures) {
            f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static void shutdown(ExecutorService pool) {
        pool.shutdownNow();
    }

    private static Set<Integer> distinct(List<Integer> ids) {
        return new HashSet<>(ids);
    }

    private static Set<Integer> idsOf(List<Transfer> transfers) {
        Set<Integer> ids = new HashSet<>();
        for (Transfer t : transfers) {
            ids.add(t.id());
        }
        return Collections.unmodifiableSet(ids);
    }
}
