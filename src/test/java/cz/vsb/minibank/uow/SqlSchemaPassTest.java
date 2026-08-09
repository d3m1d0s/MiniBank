package cz.vsb.minibank.uow;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.SimpleFeePolicy;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
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
 * A6, the columns bundled with it, and B19's guard on the transfers row, asserted against a real
 * PostgreSQL.
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
    // A6: a lost update is refused rather than silently applied
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
     * own. Asserted so the cost of A6 is on the record and not a surprise: the shop receives one
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
    // B19: the same guard on the transfers row, which A6 did not cover
    // -------------------------------------------------------------------------

    /**
     * Two tabs on one waiting payment: the cancel and the authorization cannot both land.
     *
     * The shape A6 left open. Both writers here touch only the transfers row - the cancel never
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
                customerId, accountId, EXTERNAL_IBAN.value(), 2_500, "invoice 2026/03");

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
                customerId, accountId, EXTERNAL_IBAN.value(), 3_500, null);
        Transfer pending = infra.transfers.byId(waiting).orElseThrow();
        assertEquals(TransferStatus.WAITING_AUTH, pending.status());
        assertNull(pending.fee(), "an unsettled transfer has been charged nothing, not zero");
        assertNull(pending.settledAt());
        assertNull(pending.message(), "a blank reference is stored as absent, not as an empty string");

        services.transferService.cancelPayment(customerId, waiting);

        // And the analyst's verdict, through the fraud service.
        int flagged = services.transferService.submitPaymentToIban(
                customerId, accountId, EXTERNAL_IBAN.value(), 12_000, "over the alert threshold");
        services.fraudService.decideAndUpdateAlert(
                infra.alerts.byTransferId(flagged).orElseThrow().id(),
                "APPROVE", null, null, List.of("manual-review"), "looked fine", "anna.analyst");

        FraudAlert decided = infra.alerts.byTransferId(flagged).orElseThrow();
        assertEquals(FraudAlert.DECISION_APPROVE, decided.decision(),
                "the verdict must be readable from the store, not only from the state column");
        assertEquals("anna.analyst", decided.decidedBy());
        assertNotNull(decided.resolvedAt());
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
                customerId, accountId, EXTERNAL_IBAN.value(), 3_500, null);
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
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM transfers")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Bypasses the domain entirely; the constraint is the only thing standing here. */
    private void insertRawTransfer(int accountId, String amount) throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO transfers
                         (id, source_account_id, target_iban_snapshot, amount, currency, status)
                     VALUES (nextval('transfers_id_seq'), ?, ?, ?::numeric, 'CZK', 'SENT')
                     """)) {
            ps.setInt(1, accountId);
            ps.setString(2, EXTERNAL_IBAN.value());
            ps.setString(3, amount);
            ps.executeUpdate();
        }
    }
}
