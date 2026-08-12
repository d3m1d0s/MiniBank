package cz.vsb.minibank.uow;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertState;
import cz.vsb.minibank.domain.SimpleFeePolicy;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.FraudAlertChangedException;
import cz.vsb.minibank.domain.exceptions.OptimisticLockException;
import cz.vsb.minibank.domain.exceptions.TransferChangedException;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The account version, the columns bundled with it, and the guards the transfers and
 * fraud_alerts rows carry of their own, asserted against a real PostgreSQL.
 *
 * Everything here needs a database, and for a reason: a version column that is compared inside a
 * statement cannot be tested against a fake, a CHECK constraint is not a Java rule, and a column
 * that is written but never read back is exactly the defect this pass corrects - only a round
 * trip through the store can catch it. Like {@link MinibankSqlUowTests} the whole class is
 * reported as skipped when no database is reachable, so a fresh clone stays green.
 */
public class SqlSchemaPassTest {

    private String jdbcUrl;
    private String dbUser;
    private String dbPass;

    private Bootstrap infra;

    private static boolean databaseReachable;

    /** No thread should ever wait this long; a timeout means something is wedged. */
    private static final int TIMEOUT_SECONDS = 60;

    /**
     * Which of the two racing threads plays the cancel. Claimed rather than assigned, because
     * both threads run the same Callable and neither may be told which one it is in advance.
     */
    private final AtomicBoolean cancelTaken = new AtomicBoolean(false);

    /** The same arrangement for the two analysts: which of them records the verdict. */
    private final AtomicBoolean verdictTaken = new AtomicBoolean(false);

    private static final IBAN PAYER_IBAN = new IBAN("CZ6508000000192000145399");
    private static final IBAN EXTERNAL_IBAN = new IBAN("CZ2001000000000012345678");

    @BeforeAll
    static void probeTestDatabase() {
        TestDatabase.requireSeparateFromApplicationDatabase();
        databaseReachable = TestDatabase.isReachable();
    }

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(databaseReachable, TestDatabase::unreachableMessage);

        jdbcUrl = TestDatabase.url();
        dbUser = TestDatabase.user();
        dbPass = TestDatabase.password();

        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             Statement st = conn.createStatement()) {
            st.execute("""
                    TRUNCATE TABLE fraud_alerts, transfers, beneficiaries, accounts, customers
                    RESTART IDENTITY CASCADE
                    """);
        }

        infra = new Bootstrap(jdbcUrl, dbUser, dbPass);
    }

    // -------------------------------------------------------------------------
    // A lost update is refused rather than silently applied
    // -------------------------------------------------------------------------

    /**
     * The measured leak, reproduced and then refused.
     *
     * Two threads, released together from a CyclicBarrier - the same shape
     * JsonStoreConcurrencyTest uses, for the same reason: without a barrier they drift past each
     * other and the interleaving under test never happens. Each opens its own unit of work on
     * its own JDBC connection, reads account 100 at 1 000.00, debits 100.00, and commits.
     *
     * Against the pre-fix upsert both wrote an absolute 900.00 over each other and the account
     * ended at 900.00 having been debited twice - one debit destroyed, 100.00 gone. The version
     * on the DO UPDATE arm makes the second writer's predicate false, so it changes nothing,
     * RETURNING yields no row, and the write is refused with an OptimisticLockException instead.
     *
     * Both orderings are covered by the same run and neither is assumed. If the loser commits
     * second it finds version 1 where it read 0. If it reaches its upsert first, ON CONFLICT DO
     * UPDATE blocks on the winner's row lock and, when the winner commits, PostgreSQL re-reads
     * the latest committed row and evaluates the WHERE against that rather than against this
     * transaction's snapshot. That second case is the whole reason this works under READ
     * COMMITTED with no isolation level set anywhere.
     *
     * The assertion is on the row, read back on a third connection: exactly one debit applied,
     * exactly one version bump, and the loser reported a refusal rather than a success.
     */
    @Test
    void twoTransactionsDebitingOneAccountLoseNothingAndTheSecondIsRefused() throws Exception {
        int accountId = seedAccount(Money.czk(1_000), Money.czk(1_000_000), null);

        CyclicBarrier atTheSameMoment = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<Throwable> debitOnce = () -> {
            try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
                UnitOfWork uow = scope.uow();
                Account acc = infra.accounts.byId(accountId).orElseThrow();
                acc.debit(Money.czk(100), Money.czk(0));
                infra.accounts.save(acc);

                // After the read and the in-memory debit, before the commit: this is the window
                // in which the two transactions are guaranteed to overlap. Waiting before the
                // read would let one finish entirely before the other started.
                atTheSameMoment.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                uow.commit();
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        };

        List<Future<Throwable>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(debitOnce));
            futures.add(pool.submit(debitOnce));

            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> f : futures) {
                outcomes.add(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }

            long succeeded = outcomes.stream().filter(java.util.Objects::isNull).count();
            assertEquals(1, succeeded,
                    "exactly one of the two writers may win; outcomes were " + outcomes);

            Throwable refused = outcomes.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow();
            assertTrue(refused instanceof OptimisticLockException,
                    "the loser must be refused as a stale write, not as an internal error: " + refused);
            assertTrue(refused.getMessage().contains(String.valueOf(accountId)),
                    "the refusal must name the account, for the log: " + refused.getMessage());
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, Money.czk(900).amount().compareTo(balanceOf(accountId)),
                "one debit applied, and the other refused rather than overwriting it - 900.00,"
                        + " which is also what the pre-fix code produced by destroying a debit."
                        + " The version below is what tells the two apart");
        assertEquals(1, versionOf(accountId),
                "exactly one guarded write reached the row; two would mean both debits were applied"
                        + " and one balance was overwritten");
    }

    /**
     * The same guard on the credit leg, which is the common case rather than the rare one.
     *
     * Two unrelated customers paying one in-bank shop contend on the payee's row, not on their
     * own. Asserted so the cost of the version column is on the record and not a surprise: the shop receives one
     * payment and the other payer is refused with a 409, having raced nobody they know about.
     */
    @Test
    void twoPayersOfOneInBankPayeeContendOnThePayeeRow() throws Exception {
        int shopId = seedAccount(Money.czk(1_000), Money.czk(1_000_000), null);

        CyclicBarrier atTheSameMoment = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<Throwable> creditOnce = () -> {
            try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
                UnitOfWork uow = scope.uow();
                Account shop = infra.accounts.byId(shopId).orElseThrow();
                shop.credit(Money.czk(250));
                infra.accounts.save(shop);
                atTheSameMoment.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                uow.commit();
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        };

        List<Throwable> outcomes = new ArrayList<>();
        try {
            List<Future<Throwable>> futures = List.of(pool.submit(creditOnce), pool.submit(creditOnce));
            for (Future<Throwable> f : futures) {
                outcomes.add(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, outcomes.stream().filter(java.util.Objects::isNull).count(),
                "one credit lands and the other is refused: " + outcomes);
        assertEquals(0, Money.czk(1_250).amount().compareTo(balanceOf(shopId)),
                "the payee is credited once, not twice and not zero times");
    }

    // -------------------------------------------------------------------------
    // The same guard on the transfers row, which the account version did not cover
    // -------------------------------------------------------------------------

    /**
     * Two tabs on one waiting payment: the cancel and the authorization cannot both land.
     *
     * The shape the account version left open. Both writers here touch only the transfers row - the cancel never
     * loads an account at all, and a failed OTP attempt does not either - so accounts.version
     * cannot see either of them and, before transfers got its own, the second writer simply
     * overwrote the first. Concretely: a cancel committing after an authorization wrote DECLINED
     * over SENT and blanked fee and settled_at with it, so the debit stood while the row dropped
     * out of the SENT-only day total.
     *
     * The two mutations are deliberately different, and one of them is the case a status token
     * would have missed. A failed OTP attempt leaves the status at WAITING_AUTH, so a guard of
     * the form {@code WHERE status = ?} would have let both through - which is why this column is
     * a version and not a status.
     *
     * Both orderings are covered by the one run, exactly as the account test above covers them:
     * the loser either finds version 1 where it read 0, or blocks on the row lock and is
     * evaluated against the winner's committed row when it is released.
     */
    @Test
    void cancellingAndAuthorizingOneTransferAtOnceLeavesOneOutcomeAndRefusesTheOther() throws Exception {
        int transferId = seedWaitingTransfer();

        CyclicBarrier atTheSameMoment = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<Throwable> writeOnce = () -> {
            try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
                UnitOfWork uow = scope.uow();
                Transfer t = infra.transfers.byId(transferId).orElseThrow();

                // Whichever thread gets here first decides which mutation this one is. Both are
                // real: the first is what cancelPayment does, the second what the wrong-OTP
                // branch does, and neither writes an account.
                if (t.status() == TransferStatus.WAITING_AUTH && !cancelTaken.getAndSet(true)) {
                    t.decline("Canceled by customer");
                } else {
                    t.registerFailedOtpAttempt(99);
                }
                infra.transfers.save(t);

                atTheSameMoment.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                uow.commit();
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        };

        try {
            List<Future<Throwable>> futures = List.of(pool.submit(writeOnce), pool.submit(writeOnce));
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> f : futures) {
                outcomes.add(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }

            assertEquals(1, outcomes.stream().filter(java.util.Objects::isNull).count(),
                    "exactly one of the two writers may win; outcomes were " + outcomes);

            Throwable refused = outcomes.stream()
                    .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
            assertTrue(refused instanceof TransferChangedException,
                    "the loser must be refused as a stale transfer write, not as an internal"
                            + " error and not as the account conflict: " + refused);
            assertTrue(refused.getMessage().contains(String.valueOf(transferId)),
                    "the refusal must name the transfer, for the log: " + refused.getMessage());
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, versionOfTransfer(transferId),
                "exactly one guarded write reached the row; two would mean one of them was"
                        + " silently overwritten, which is the defect this column exists to refuse");
    }

    /**
     * The token survives a round trip, which is what makes the write after a load guarded at all.
     *
     * Asserted because the load path is the half that fails silently. A transfer that came back
     * without its version would carry 0, the next update would compare against the version of a
     * row nobody has written yet, and the guard would either refuse a legitimate write or - if
     * the row really were at 0 - pass one it should have refused.
     */
    @Test
    void theTransferVersionComesBackFromTheStoreAndCountsTheGuardedWrites() throws Exception {
        int transferId = seedWaitingTransfer();

        // Creating a payment is a single write, and an insert takes the column default: the
        // DO UPDATE arm is the only thing that bumps. So a transfer that has been written once
        // has never yet been guarded against anything.
        assertEquals(0, versionOfTransfer(transferId),
                "an insert must leave the default rather than choosing a version of its own");

        Transfer loaded = infra.transfers.byId(transferId).orElseThrow();
        assertEquals(0, loaded.version(),
                "the aggregate must carry the version the store holds after a load");

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow = scope.uow();
            Transfer t = infra.transfers.byId(transferId).orElseThrow();
            t.decline("Canceled by customer");
            infra.transfers.save(t);
            uow.commit();
        }

        assertEquals(1, versionOfTransfer(transferId),
                "one guarded write, one version");
        assertEquals(1, infra.transfers.byId(transferId).orElseThrow().version(),
                "and the load brings the new one back, which is what lets the write after it"
                        + " be guarded in turn");
    }

    // -------------------------------------------------------------------------
    // And the same guard on the fraud_alerts row, which neither of the other two covered
    // -------------------------------------------------------------------------

    /**
     * Two analysts on one alert, on the paths where the transfers row is never written.
     *
     * The shape both versions above left open. FraudAlert's own guards - approve refuses anything
     * but NEW, markSuspicious refuses an alert already SUSPICIOUS - are checked against each
     * transaction's own snapshot and the write is deferred to commit under READ COMMITTED, so
     * between two transactions they see nothing. What was catching these races was
     * transfers.version, and only when both analysts happened to write that row as well.
     *
     * So the fixture takes that away, and takes it away the way the application does rather than
     * by contrivance: the customer cancels the held payment, which leaves the transfer DECLINED
     * with its alert still open in the queue. From there
     * {@code FraudApplicationService.decline} records the verdict on the alert alone - its guard
     * excludes SENT and DECLINED so an analyst's wording cannot overwrite the customer's own - and
     * the REQUEST_CONFIRMATION route writes no transfer on any status. Neither racer below touches
     * the transfers row, so before fraud_alerts had a version of its own nothing looked at all and
     * the second commit simply won.
     *
     * The two mutations are deliberately different, and the annotation is the one a state token
     * would have missed: it changes no state, which is exactly why it was able to write NEW, no
     * decision and no resolved_at back over a verdict and reopen a decided alert.
     *
     * Both orderings are covered by the one run, exactly as the two cases above cover them: the
     * loser either finds version 1 where it read 0, or blocks on the row lock and is evaluated
     * against the winner's committed row when it is released.
     */
    @Test
    void twoAnalystsWritingOneAlertWithoutTouchingItsTransferLeaveOneOutcomeAndRefuseTheOther()
            throws Exception {
        BootstrapServices services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts, infra.uowFactory);
        HeldPayment held = seedHeldPaymentWithAlert(services);

        services.transferService.cancelPayment(held.customerId(), held.transferId());
        assertEquals(TransferStatus.DECLINED,
                infra.transfers.byId(held.transferId()).orElseThrow().status(),
                "the fixture must put the payment beyond the fraud desk's reach, or one of the two"
                        + " writers below would touch the transfers row and that row's own version"
                        + " would be what refused the race");
        assertEquals(FraudAlertState.NEW, infra.alerts.byId(held.alertId()).orElseThrow().state(),
                "and must leave the alert open, or there is no decision left to race over");

        CyclicBarrier atTheSameMoment = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<Throwable> writeOnce = () -> {
            try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
                UnitOfWork uow = scope.uow();
                FraudAlert alert = infra.alerts.byId(held.alertId()).orElseThrow();

                // Whichever thread gets here first decides which analyst this one is. Both are
                // real: the first is what decline() does once the transfer is out of reach, the
                // second what the REQUEST_CONFIRMATION arm does, and neither writes a transfer.
                if (!verdictTaken.getAndSet(true)) {
                    alert.markSuspicious("Confirmed by the card scheme", "anna.analyst",
                            Instant.now());
                } else {
                    alert.updateNotes("Called the customer back");
                }
                infra.alerts.save(alert);

                atTheSameMoment.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                uow.commit();
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        };

        try {
            List<Future<Throwable>> futures = List.of(pool.submit(writeOnce), pool.submit(writeOnce));
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> f : futures) {
                outcomes.add(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }

            assertEquals(1, outcomes.stream().filter(java.util.Objects::isNull).count(),
                    "exactly one of the two writers may win; outcomes were " + outcomes);

            Throwable refused = outcomes.stream()
                    .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
            assertTrue(refused instanceof FraudAlertChangedException,
                    "the loser must be refused as a stale alert write, not as an internal error"
                            + " and not as the account or the transfer conflict: " + refused);
            assertTrue(refused.getMessage().contains(String.valueOf(held.alertId())),
                    "the refusal must name the alert, for the log: " + refused.getMessage());
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, versionOfAlert(held.alertId()),
                "exactly one guarded write reached the row; two would mean one of them was"
                        + " silently overwritten - either a verdict buried by an annotation that"
                        + " reopens the alert, or the reverse");
    }

    /**
     * The token survives a round trip, and one analyst deciding alone is untouched.
     *
     * Both halves matter and the second is not padding. A decision writes the alert TWICE in one
     * unit of work - {@code decideAndUpdateAlert} saves in the verdict arm and again after the
     * assignee, tags and notes block - so a guard whose write-back was missing would refuse every
     * decision this application makes, on the second save, with nobody racing anybody. The load
     * path fails the other way and just as silently: an alert that came back without its version
     * would carry 0, and the next update would be compared against the version of a row nobody has
     * written yet.
     */
    @Test
    void theAlertVersionComesBackFromTheStoreAndAnUncontendedDecisionStillLands() throws Exception {
        BootstrapServices services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts, infra.uowFactory);
        HeldPayment held = seedHeldPaymentWithAlert(services);

        // Raising an alert is a single write, and an insert takes the column default: the
        // DO UPDATE arm is the only thing that bumps. So an alert that has been written once has
        // never yet been guarded against anything.
        assertEquals(0, versionOfAlert(held.alertId()),
                "an insert must leave the default rather than choosing a version of its own");
        assertEquals(0, infra.alerts.byId(held.alertId()).orElseThrow().version(),
                "the aggregate must carry the version the store holds after a load");

        services.fraudService.decideAndUpdateAlert(
                held.alertId(), "APPROVE", null, "anna.analyst", List.of("manual-review"),
                "looked fine", "anna.analyst");

        assertEquals(2, versionOfAlert(held.alertId()),
                "the verdict and the metadata are two guarded writes in one unit of work, and the"
                        + " second is only possible because the first handed back the version it"
                        + " left behind");

        FraudAlert decided = infra.alerts.byId(held.alertId()).orElseThrow();
        assertEquals(FraudAlertState.OK, decided.state());
        assertEquals(FraudAlert.DECISION_APPROVE, decided.decision());
        assertEquals("looked fine", decided.notes());
        assertEquals(2, decided.version(),
                "and the load brings the new one back, which is what lets the write after it be"
                        + " guarded in turn");
        assertEquals(TransferStatus.WAITING_AUTH,
                infra.transfers.byId(held.transferId()).orElseThrow().status(),
                "and the payment really was released for the customer's own confirmation step");
    }

    // -------------------------------------------------------------------------
    // The CHECK constraint
    // -------------------------------------------------------------------------

    /**
     * The invariant Transfer's constructor enforces, now also enforced against anything that
     * writes the table without going through the domain.
     *
     * Written as raw SQL on purpose. Every Java path is already refused - the constructor throws
     * DataIntegrityException on a non-positive amount, on both the creation and the rehydration
     * path - so a test that went through the domain would prove the Java rule again and say
     * nothing about the column. This is a psql session, a report job or a bad migration.
     */
    @Test
    void aNonPositiveAmountCannotBeInsertedEvenBehindTheDomainsBack() throws Exception {
        int accountId = seedAccount(Money.czk(1_000), Money.czk(1_000_000), null);

        for (String amount : new String[]{"0.00", "-1.00", "-0.01"}) {
            SQLException refused = assertThrows(SQLException.class,
                    () -> insertRawTransfer(accountId, amount),
                    "an amount of " + amount + " must be refused by the database");
            assertTrue(String.valueOf(refused.getMessage()).contains("transfers_amount_positive"),
                    "refused by the named CHECK rather than by something else: " + refused.getMessage());
        }

        assertEquals(0, countTransfers(), "no row may survive a refused insert");

        // And the constraint is not so tight that it refuses the smallest real payment.
        insertRawTransfer(accountId, "0.01");
        assertEquals(1, countTransfers());
    }

    /**
     * The same shape for the currency, and this one closes the route the foreign row actually
     * came in by.
     *
     * The column has always been VARCHAR(3) and would take any three characters, so a psql
     * session was all it took to put a transfer denominated in something this bank does not keep
     * into the table. That row then came back out as a genuine foreign amount beside a fee that
     * is always rebuilt as crowns, and adding the two raised a bare "Currency mismatch" on the
     * customer's own payment screen. Transfer's constructor now refuses such a row on load; this
     * stops it being writable in the first place.
     */
    @Test
    void aTransferInAnotherCurrencyCannotBeInsertedEvenBehindTheDomainsBack() throws Exception {
        int accountId = seedAccount(Money.czk(1_000), Money.czk(1_000_000), null);

        for (String currency : new String[]{"EUR", "USD", "czk", ""}) {
            SQLException refused = assertThrows(SQLException.class,
                    () -> insertRawTransfer(accountId, "100.00", currency),
                    "a currency of '" + currency + "' must be refused by the database");
            assertTrue(String.valueOf(refused.getMessage()).contains("transfers_currency_czk"),
                    "refused by the named CHECK rather than by something else: " + refused.getMessage());
        }

        assertEquals(0, countTransfers(), "no row may survive a refused insert");

        insertRawTransfer(accountId, "100.00", "CZK");
        assertEquals(1, countTransfers());
    }

    /**
     * The stored fee, which is the one number in the row that nothing re-checks on the way out.
     *
     * Raw SQL for the same reason as the two cases above. Every Java path takes its fee from a
     * {@link cz.vsb.minibank.domain.FeePolicy}, whose contract is that a fee is never negative, so
     * a test that went through the domain would prove that contract again and say nothing about
     * the column. Transfer.hydrateSettlement validates nothing on load, deliberately - a loader
     * that refused a legacy row would make the whole store unreadable - so a fee written by hand
     * comes back exactly as written on both backends, reaches the details endpoint and the fraud
     * desk through Transfer.feeFor, and leaves the receipt understating a debit that really
     * happened. The stored fee exists so a historical charge reconciles with the balance movement;
     * a negative one is precisely the row that does not.
     *
     * The last two inserts are what makes this the right constraint rather than merely a strict
     * one. Zero is a real fee, and NULL is the state every unsettled transfer is in - it says the
     * payment has not been charged yet, which is a different fact from having been charged
     * nothing, and a constraint that refused it would refuse every payment waiting for a code.
     */
    @Test
    void aNegativeStoredFeeIsRefusedWhileAnAbsentOneIsStillAccepted() throws Exception {
        int accountId = seedAccount(Money.czk(1_000), Money.czk(1_000_000), null);

        for (String fee : new String[]{"-0.01", "-25.00"}) {
            SQLException refused = assertThrows(SQLException.class,
                    () -> insertRawTransferWithFee(accountId, fee),
                    "a stored fee of " + fee + " must be refused by the database");
            assertTrue(String.valueOf(refused.getMessage()).contains("transfers_fee_not_negative"),
                    "refused by the named CHECK rather than by something else: " + refused.getMessage());
        }

        assertEquals(0, countTransfers(), "no row may survive a refused insert");

        insertRawTransferWithFee(accountId, "0.00");
        insertRawTransferWithFee(accountId, null);
        assertEquals(2, countTransfers(),
                "a transfer charged nothing and a transfer not charged yet are both legitimate"
                        + " rows, and the second one is most of the table");
    }

    /**
     * The status column has no CHECK, deliberately, and this is what stands in its place.
     *
     * A CHECK on an enum column makes every future value a two-place change and would contradict
     * what db/migrate/hold-alerted-transfers.sql argues about adding one. The real defect was
     * never that the database allowed a bad string - it was that the loader swallowed it: the
     * parse and the hydrate call shared one catch, so an unreadable status came back as the
     * constructor's CREATED and took the creation instant, the authorization method, the decline
     * reason and both OTP fields down with it.
     *
     * So the guard is in Java and this is the test that says so, written here because this is
     * where a writer that bypasses the domain lives.
     */
    @Test
    void aStatusNobodyCanReadIsRefusedWhenTheRowIsLoaded() throws Exception {
        int accountId = seedAccount(Money.czk(1_000), Money.czk(1_000_000), null);
        int transferId = insertRawTransfer(accountId, "100.00", "CZK", "NOT_A_STATUS");

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            DataIntegrityException refused = assertThrows(DataIntegrityException.class,
                    () -> infra.transfers.byId(transferId));
            assertTrue(refused.getMessage().contains("NOT_A_STATUS"),
                    "the refusal must name the value: " + refused.getMessage());
            assertTrue(refused.getMessage().contains(String.valueOf(transferId)),
                    "and the row, so a corrupt store can be found: " + refused.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // The columns a stored row must carry
    // -------------------------------------------------------------------------

    /**
     * Neither table takes a row that does not say when it was created.
     *
     * Both loaders already refuse one. Transfer.hydrateForLoad and FraudAlert.hydrateForLoad throw
     * DataIntegrityException naming the row rather than stamping the load instant over the gap,
     * because a row created "when it was last read" sorts first in a list that promises the newest
     * and moves the date filters the analyst's queue runs on, differently on every read. So the
     * column was able to hold a value the application refuses to read back, and this is the
     * writer's half of that pair: every writer inside the application stamps it unconditionally,
     * which leaves psql and a bad script as the only producers - the same writer the two CHECKs
     * above were added against.
     *
     * fraud_alerts is the worse of the two and is here for that reason. SqlFraudAlertRepository
     * loads the whole queue and maps every row in it, so one alert written without a creation
     * instant does not spoil one alert: it answers the analyst's entire queue with a 500 until
     * somebody goes and finds it by hand.
     *
     * Matched on SQLState rather than on a constraint name, because a NOT NULL column has none to
     * match. 23502 is not_null_violation, and it is what tells a refusal by this rule apart from a
     * refusal by the foreign key or by one of the CHECKs above.
     */
    @Test
    void neitherTableTakesARowWithNoCreationInstant() throws Exception {
        int accountId = seedAccount(Money.czk(1_000), Money.czk(1_000_000), null);

        SQLException undatedTransfer = assertThrows(SQLException.class,
                () -> insertRawTransferWithoutCreationInstant(accountId),
                "a transfer with no creation instant must be refused by the database");
        assertEquals("23502", undatedTransfer.getSQLState(),
                "refused as a not-null violation rather than by something else: "
                        + undatedTransfer.getMessage());
        assertTrue(String.valueOf(undatedTransfer.getMessage()).contains("created_at"),
                "and the refusal must name the column: " + undatedTransfer.getMessage());
        assertEquals(0, countTransfers(), "no row may survive a refused insert");

        int transferId = insertRawTransfer(accountId, "100.00", "CZK", "HELD_FOR_REVIEW");

        SQLException undatedAlert = assertThrows(SQLException.class,
                () -> insertRawAlert(transferId, false),
                "an alert with no creation instant must be refused by the database");
        assertEquals("23502", undatedAlert.getSQLState(),
                "refused as a not-null violation rather than by something else: "
                        + undatedAlert.getMessage());
        assertTrue(String.valueOf(undatedAlert.getMessage()).contains("created_at"),
                "and the refusal must name the column: " + undatedAlert.getMessage());
        assertEquals(0, countAlerts(), "no row may survive a refused insert");

        // And the rule is not so tight that it refuses the alert a normal writer produces.
        insertRawAlert(transferId, true);
        assertEquals(1, countAlerts());
    }

    // -------------------------------------------------------------------------
    // The new columns actually reach the database and come back
    // -------------------------------------------------------------------------

    /**
     * A round trip for every column this pass added, through the real repositories.
     *
     * The one thing that catches a column wired into the domain and forgotten in the upsert or
     * in the SELECT list, which is how fraud_alerts.decision and resolved_at came to be declared
     * in the schema, written by nothing and read by nothing since the table was created.
     */
    @Test
    void everyNewColumnSurvivesAWriteAndAReadBack() throws Exception {
        // The production wiring: SimpleFeePolicy, so the stored fee is a real number rather
        // than a zero that would prove nothing about whether it was stored.
        BootstrapServices services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts, infra.uowFactory);

        int customerId;
        int accountId;

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow = scope.uow();
            customerId = infra.customers.nextId();
            Customer c = new Customer(customerId, "Schema Probe", "schema@example.com",
                    new Address("Hlavni 1", "Ostrava"));
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            // A soft tier of its own, below the ceiling, so the account really has two tiers.
            infra.accounts.save(new Account(accountId, PAYER_IBAN,
                    Money.czk(500_000), Money.czk(400_000), Money.czk(3_000)));
            c.addAccountId(accountId);
            infra.customers.save(c);
            uow.commit();
        }

        Account reloaded = infra.accounts.byId(accountId).orElseThrow();
        assertNotNull(reloaded.softDailyThreshold(), "the per-account soft tier must come back");
        assertEquals(0, Money.czk(3_000).amount().compareTo(reloaded.softDailyThreshold().amount()));

        // 2 500 is under this account's own 3 000 tier and under every untrusted threshold, so
        // it settles at creation and carries a fee and a settlement instant.
        int settled = services.transferService.submitPaymentToIban(
                customerId, accountId, EXTERNAL_IBAN.value(), 2_500, "invoice 2026/03").transferId();

        Transfer t = infra.transfers.byId(settled).orElseThrow();
        assertEquals(TransferStatus.SENT, t.status());
        assertNotNull(t.fee(), "a settled transfer must carry the fee it was charged");
        assertEquals(0, new SimpleFeePolicy().compute(Money.czk(2_500)).amount()
                .compareTo(t.fee().amount()));
        assertNotNull(t.settledAt(), "a settled transfer must record when the money moved");
        assertEquals("invoice 2026/03", t.message(),
                "the payment reference must survive the store, not be accepted and dropped");

        // 3 500 crosses this account's own tier, so it waits - and while it waits it has no fee
        // and no settlement instant, which must round-trip as null rather than as zero.
        int waiting = services.transferService.submitPaymentToIban(
                customerId, accountId, EXTERNAL_IBAN.value(), 3_500, null).transferId();
        Transfer pending = infra.transfers.byId(waiting).orElseThrow();
        assertEquals(TransferStatus.WAITING_AUTH, pending.status());
        assertNull(pending.fee(), "an unsettled transfer has been charged nothing, not zero");
        assertNull(pending.settledAt());
        assertNull(pending.message(), "a blank reference is stored as absent, not as an empty string");

        services.transferService.cancelPayment(customerId, waiting);

        // And the analyst's verdict, through the fraud service.
        int flagged = services.transferService.submitPaymentToIban(
                customerId, accountId, EXTERNAL_IBAN.value(), 12_000, "over the alert threshold").transferId();
        services.fraudService.decideAndUpdateAlert(
                infra.alerts.byTransferId(flagged).orElseThrow().id(),
                "APPROVE", null, null, List.of("manual-review"), "looked fine", "anna.analyst");

        FraudAlert decided = infra.alerts.byTransferId(flagged).orElseThrow();
        assertEquals(FraudAlert.DECISION_APPROVE, decided.decision(),
                "the verdict must be readable from the store, not only from the state column");
        assertEquals("anna.analyst", decided.decidedBy());
        assertNotNull(decided.resolvedAt());
    }

    // -------------------------------------------------------------------------
    // Only a payment that moves money writes the account row
    // -------------------------------------------------------------------------

    /**
     * Submitting a payment that settles nothing must leave the account row alone.
     *
     * accounts.version is the serialization point on a balance: every guarded write bumps it and
     * every writer that read the older number is refused with a 409. Two of the three creation
     * branches move no money - the payment is held for an analyst, or it waits for the customer's
     * code - and both used to call accounts.save regardless. On SQL that is a real upsert, which
     * rewrites identical values and bumps the column, so submitting a payment raced every
     * authorization of every older transfer from the same account and could lose to one over an
     * operation that never touched the balance.
     *
     * Asserted against the column rather than against a mock of the repository, because the claim
     * is about what reaches the store: verifying that save was not called would pin the call site
     * and say nothing about whether the row moved. The settling case is in the same test on
     * purpose - a change that simply stopped saving accounts would satisfy the first two
     * assertions and fail the last one.
     */
    @Test
    void onlyAPaymentThatMovesMoneyBumpsTheAccountVersion() throws Exception {
        BootstrapServices services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts, infra.uowFactory);

        int customerId;
        int accountId;

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow = scope.uow();
            customerId = infra.customers.nextId();
            Customer c = new Customer(customerId, "Version Probe", "version@example.com",
                    new Address("Hlavni 1", "Ostrava"));
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            // A soft tier far below the ceiling, so an amount can cross the tier without coming
            // anywhere near the daily limit and no second rule can be what routed the payment.
            infra.accounts.save(new Account(accountId, PAYER_IBAN,
                    Money.czk(500_000), Money.czk(400_000), Money.czk(3_000)));
            c.addAccountId(accountId);
            infra.customers.save(c);
            uow.commit();
        }

        // The insert takes the column default, and SqlCustomerRepository's ownership UPDATE after
        // it writes customer_id, which the guarded upsert never touches. So the row arrives here
        // having been guarded against nothing, and every later number is a write this test caused.
        int afterSeeding = versionOf(accountId);
        assertEquals(0, afterSeeding, "seeding an account is one insert and no guarded write");

        // 3 500 crosses this account's own 3 000 tier and nothing else: below the untrusted
        // single-amount threshold, below the alert threshold, far below the ceiling.
        int waiting = services.transferService.submitPaymentToIban(
                customerId, accountId, EXTERNAL_IBAN.value(), 3_500, null).transferId();
        assertEquals(TransferStatus.WAITING_AUTH,
                infra.transfers.byId(waiting).orElseThrow().status(),
                "the fixture must park the payment, or the case under test never happens");
        assertEquals(afterSeeding, versionOf(accountId),
                "a payment waiting for a code has moved no money, so the account row must be"
                        + " untouched: a bump here makes submitting a payment contend with every"
                        + " authorization on the same account, for a write with nothing in it");

        // 12 000 passes the cumulative alert threshold for an untrusted payee, so it is held.
        int held = services.transferService.submitPaymentToIban(
                customerId, accountId, EXTERNAL_IBAN.value(), 12_000, null).transferId();
        assertEquals(TransferStatus.HELD_FOR_REVIEW,
                infra.transfers.byId(held).orElseThrow().status(),
                "the fixture must raise an alert, or the case under test never happens");
        assertEquals(afterSeeding, versionOf(accountId),
                "and neither has a payment an analyst has still to look at");

        // 2 500 stays under the tier, so it settles at creation - and that one really does write.
        int settled = services.transferService.submitPaymentToIban(
                customerId, accountId, EXTERNAL_IBAN.value(), 2_500, null).transferId();
        assertEquals(TransferStatus.SENT,
                infra.transfers.byId(settled).orElseThrow().status(),
                "the fixture must settle this one, or the last assertion proves nothing");
        assertEquals(afterSeeding + 1, versionOf(accountId),
                "the debit is still a guarded write: dropping the two saves that persisted nothing"
                        + " must not drop the one that persists a balance");
    }

    // ------------------------------------------------------------------
    // fixture and plumbing
    // ------------------------------------------------------------------

    private int seedAccount(Money balance, Money dailyLimit, Money softTier) {
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow = scope.uow();
            int customerId = infra.customers.nextId();
            Customer c = new Customer(customerId, "Race Probe", "race@example.com",
                    new Address("Hlavni 1", "Ostrava"));
            infra.customers.save(c);

            int accountId = infra.accounts.nextId();
            infra.accounts.save(new Account(accountId, PAYER_IBAN, balance, dailyLimit, softTier));
            c.addAccountId(accountId);
            infra.customers.save(c);

            uow.commit();
            return accountId;
        }
    }

    /** Read on a connection of its own, so no identity map and no open transaction can answer. */
    private java.math.BigDecimal balanceOf(int accountId) throws SQLException {
        return readOne("SELECT balance_czk FROM accounts WHERE id = ?", accountId,
                rs -> rs.getBigDecimal(1));
    }

    private int versionOf(int accountId) throws SQLException {
        return readOne("SELECT version FROM accounts WHERE id = ?", accountId, rs -> rs.getInt(1));
    }

    private int versionOfTransfer(int transferId) throws SQLException {
        return readOne("SELECT version FROM transfers WHERE id = ?", transferId, rs -> rs.getInt(1));
    }

    private int versionOfAlert(int alertId) throws SQLException {
        return readOne("SELECT version FROM fraud_alerts WHERE id = ?", alertId, rs -> rs.getInt(1));
    }

    /** The customer, the payment an analyst still has to look at, and the alert holding it. */
    private record HeldPayment(int customerId, int transferId, int alertId) { }

    /**
     * A payment held for review, with the alert the rules themselves raised on it.
     *
     * 12 000 passes the cumulative alert threshold for an untrusted payee, which is the same
     * amount {@link #onlyAPaymentThatMovesMoneyBumpsTheAccountVersion} relies on for the same
     * reason: the alert has to be the rules' own, not one written behind the domain's back, or
     * the fixture would not be the case under test.
     */
    private HeldPayment seedHeldPaymentWithAlert(BootstrapServices services) {
        int customerId;
        int accountId;

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            customerId = infra.customers.nextId();
            Customer c = new Customer(customerId, "Alert Probe", "alert@example.com",
                    new Address("Hlavni 1", "Ostrava"));
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            infra.accounts.save(new Account(accountId, PAYER_IBAN,
                    Money.czk(500_000), Money.czk(400_000), Money.czk(3_000)));
            c.addAccountId(accountId);
            infra.customers.save(c);
            scope.uow().commit();
        }

        int transferId = services.transferService.submitPaymentToIban(
                customerId, accountId, EXTERNAL_IBAN.value(), 12_000, null).transferId();
        assertEquals(TransferStatus.HELD_FOR_REVIEW,
                infra.transfers.byId(transferId).orElseThrow().status(),
                "the fixture must hold the payment, or there is no alert to write");

        int alertId = infra.alerts.byTransferId(transferId)
                .orElseThrow(() -> new AssertionError("a held payment must carry an alert")).id();
        return new HeldPayment(customerId, transferId, alertId);
    }

    /**
     * A payment parked at WAITING_AUTH, through the production services.
     *
     * The account gets a soft tier of 3 000 and the payment is 3 500, so it crosses that tier and
     * waits. Below every other threshold on purpose: nothing else may be what stopped it, or the
     * fixture would be testing the wrong rule.
     */
    private int seedWaitingTransfer() {
        BootstrapServices services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts, infra.uowFactory);

        int customerId;
        int accountId;

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            customerId = infra.customers.nextId();
            Customer c = new Customer(customerId, "Race Probe", "race@example.com",
                    new Address("Hlavni 1", "Ostrava"));
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            infra.accounts.save(new Account(accountId, PAYER_IBAN,
                    Money.czk(500_000), Money.czk(400_000), Money.czk(3_000)));
            c.addAccountId(accountId);
            infra.customers.save(c);
            scope.uow().commit();
        }

        int transferId = services.transferService.submitPaymentToIban(
                customerId, accountId, EXTERNAL_IBAN.value(), 3_500, null).transferId();
        assertEquals(TransferStatus.WAITING_AUTH,
                infra.transfers.byId(transferId).orElseThrow().status(),
                "the fixture must park the payment, or the race under test never happens");
        return transferId;
    }

    private interface RowReader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    private <T> T readOne(String sql, int id, RowReader<T> reader) throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no row for id " + id);
                return reader.read(rs);
            }
        }
    }

    private int countTransfers() throws SQLException {
        return countRows("transfers");
    }

    private int countAlerts() throws SQLException {
        return countRows("fraud_alerts");
    }

    /** The table name is a literal from this class, never a value, so no injection is possible. */
    private int countRows(String table) throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Bypasses the domain entirely; the constraint is the only thing standing here. */
    private void insertRawTransfer(int accountId, String amount) throws SQLException {
        insertRawTransfer(accountId, amount, "CZK");
    }

    private void insertRawTransfer(int accountId, String amount, String currency) throws SQLException {
        insertRawTransfer(accountId, amount, currency, "SENT");
    }

    /** Returns the id the sequence gave the row, so a loader can be pointed at it. */
    private int insertRawTransfer(int accountId, String amount, String currency, String status)
            throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO transfers
                         (id, source_account_id, target_iban_snapshot, amount, currency, status,
                          created_at)
                     VALUES (nextval('transfers_id_seq'), ?, ?, ?::numeric, ?, ?, now())
                     RETURNING id
                     """)) {
            ps.setInt(1, accountId);
            ps.setString(2, EXTERNAL_IBAN.value());
            ps.setString(3, amount);
            ps.setString(4, currency);
            ps.setString(5, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * A settled row whose fee is written straight into the column, null included.
     *
     * A separate statement rather than a fifth parameter on the one above, so its three existing
     * call sites are left alone. The amount is fixed at 100.00 because this fixture is about the
     * fee: varying both would leave a refusal ambiguous between the two CHECKs.
     */
    private void insertRawTransferWithFee(int accountId, String fee) throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO transfers
                         (id, source_account_id, target_iban_snapshot, amount, currency, status,
                          fee, created_at)
                     VALUES (nextval('transfers_id_seq'), ?, ?, 100.00, 'CZK', 'SENT',
                             ?::numeric, now())
                     """)) {
            ps.setInt(1, accountId);
            ps.setString(2, EXTERNAL_IBAN.value());
            if (fee != null) {
                ps.setString(3, fee);
            } else {
                ps.setNull(3, java.sql.Types.NUMERIC);
            }
            ps.executeUpdate();
        }
    }

    /**
     * The same insert with created_at simply left out of the column list, which is the shape a
     * hand-written INSERT actually takes rather than an explicit NULL nobody would type.
     */
    private void insertRawTransferWithoutCreationInstant(int accountId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO transfers
                         (id, source_account_id, target_iban_snapshot, amount, currency, status)
                     VALUES (nextval('transfers_id_seq'), ?, ?, 100.00, 'CZK', 'SENT')
                     """)) {
            ps.setInt(1, accountId);
            ps.setString(2, EXTERNAL_IBAN.value());
            ps.executeUpdate();
        }
    }

    /** An alert filed behind the fraud service's back, with or without its creation instant. */
    private void insertRawAlert(int transferId, boolean dated) throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO fraud_alerts (id, transfer_id, state, reason, created_at)
                     VALUES (nextval('fraud_alerts_id_seq'), ?, 'NEW', 'filed by hand', ?)
                     """)) {
            ps.setInt(1, transferId);
            if (dated) {
                ps.setTimestamp(2, java.sql.Timestamp.from(Instant.now()));
            } else {
                ps.setNull(2, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            }
            ps.executeUpdate();
        }
    }
}
