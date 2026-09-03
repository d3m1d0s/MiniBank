package cz.vsb.minibank.demo;

import cz.vsb.minibank.domain.customer.*;
import cz.vsb.minibank.domain.fee.*;
import cz.vsb.minibank.domain.fraud.*;
import cz.vsb.minibank.domain.transfer.*;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that DemoScenario produces the intended dataset and that seeding
 * twice changes nothing.
 */
class DemoScenarioTest {

    @TempDir
    Path tempDir;

    private Bootstrap infra;
    private DemoScenario scenario;
    private final FeePolicy feePolicy = new SimpleFeePolicy();

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());
        scenario = new DemoScenario(
                infra.customers, infra.accounts, infra.transfers, infra.alerts,
                infra.uowFactory, feePolicy);
    }

    @Test
    void seedCreatesTheCustomerWithTwoAccountsAndFourBeneficiaries() {
        int customerId = scenario.seed();

        Customer customer = infra.customers.byId(customerId).orElseThrow();
        assertEquals(2, customer.accountIds().size());
        assertEquals(4, customer.beneficiaries().size());
        assertTrue(customer.beneficiaries().stream().anyMatch(Beneficiary::trusted));
        assertTrue(customer.beneficiaries().stream().anyMatch(b -> !b.trusted()));
        // The third payee is the customer's own second account, and it is the only one whose IBAN
        // this bank holds. Without it no seeded payment stays inside the bank, so the credit leg
        // is never taken and every screen that says where the money went has one answer for every
        // row it can draw.
        assertTrue(
                customer.beneficiaries().stream()
                        .anyMatch(b -> b.iban().equals(DemoScenario.SECONDARY_IBAN)),
                "One payee must be an account this bank holds");
    }

    /**
     * One ceiling for the person, and no soft tier of their own.
     *
     * The dataset used to give each account a ceiling of its own - 40 000 and 8 000 - which is
     * two allowances for one customer and 48 000 spendable behind a 40 000 rule. It also gave the
     * smaller account a 3 000 soft tier, which existed only to justify a column. What is left is
     * a single ceiling, and a soft tier that is absent so the bank-wide 15 000 applies: the
     * threshold DemoRunner's script is written against.
     */
    @Test
    void theCustomerCarriesOneCeilingAndNoSoftTierOfTheirOwn() {
        int customerId = scenario.seed();

        Customer customer = infra.customers.byId(customerId).orElseThrow();
        assertEquals(Money.czk(40_000), customer.dailyLimit(),
                "one ceiling, above the 15 000 the script crosses and above its 23 200 peak");
        assertNull(customer.softDailyThreshold(),
                "no override: this customer rides the bank-wide soft tier");
    }

    @Test
    void theSettledTransfersDebitTheAccountUsingTheCurrentFeePolicy() {
        scenario.seed();

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        Account savings = infra.accounts.byIban(DemoScenario.SECONDARY_IBAN).orElseThrow();

        // Summed off the seeded rows rather than written down, because the history is a table in
        // DemoScenario and restating its amounts here would only assert that two copies of one
        // list agree. What is being checked is that each row was put through the domain: a seed
        // that debited the amounts and skipped the fee lands short of this by the fee.
        Money charged = charged(primary);
        assertTrue(charged.gt(Money.czk(0)), "The seeded history must contain settled payments");

        // The current account is paid as well as paying: the savings account sends it money, and
        // that leg lands here whole because the fee is charged to whoever pays.
        Money received = receivedBy(primary, savings);
        assertTrue(received.gt(Money.czk(0)), "The savings account must pay the current one");

        // 50 000, which is deliberately above the customer's 40 000 ceiling: an opening balance
        // below it would refuse a large payment for want of funds and the ceiling would never get
        // to speak, so the top of the three outcomes would be unreachable on this dataset.
        assertEquals(Money.czk(50_000).minus(charged).plus(received), primary.balance(),
                "The opening balance must be reduced by each settled amount plus its computed fee");
    }

    /** Everything one account paid out, amount plus the fee the current policy puts on it. */
    private Money charged(Account payer) {
        return infra.transfers.bySourceAccount(payer.id()).stream()
                .filter(t -> t.status() == TransferStatus.SENT)
                .map(t -> t.amount().plus(feePolicy.compute(t.amount())))
                .reduce(Money.czk(0), Money::plus);
    }

    /** Everything that arrived at one account from the customer's other one, whole. */
    private Money receivedBy(Account payee, Account payer) {
        return infra.transfers.bySourceAccount(payer.id()).stream()
                .filter(t -> t.status() == TransferStatus.SENT)
                .filter(t -> t.targetIbanSnapshot().equals(payee.iban().value()))
                .map(Transfer::amount)
                .reduce(Money.czk(0), Money::plus);
    }

    /**
     * The shape of the dataset: a fortnight of settled payments, and the three that are not
     * history because a screen can still act on them.
     */
    @Test
    void seedProducesAFortnightOfHistoryPlusOneHeldOneWithdrawnAndOneAwaitingTransfer() {
        scenario.seed();

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        List<Transfer> history = infra.transfers.bySourceAccount(primary.id());

        assertEquals(12, history.size());
        assertEquals(9, history.stream().filter(t -> t.status() == TransferStatus.SENT).count());
        // Held, not waiting. A seeded alert on a transfer the customer could confirm at will
        // would be exactly the shape the review gate exists to make impossible.
        assertEquals(1, history.stream().filter(t -> t.status() == TransferStatus.HELD_FOR_REVIEW).count());
        assertEquals(1, history.stream().filter(t -> t.status() == TransferStatus.DECLINED).count());
        assertEquals(1, history.stream().filter(t -> t.status() == TransferStatus.WAITING_AUTH).count());

        Transfer flagged = history.stream()
                .filter(t -> t.status() == TransferStatus.HELD_FOR_REVIEW)
                .findFirst().orElseThrow();
        FraudAlert open = infra.alerts.all().stream()
                .filter(a -> a.state() == FraudAlertState.NEW)
                .findFirst().orElseThrow();
        assertEquals(flagged.id(), open.transferId(),
                "The open alert must point at the held transfer");
        assertNull(flagged.authValidUntil(),
                "A held transfer has no authorization window: the five minutes must not run "
                        + "out while the alert sits in the analyst's queue");
    }

    /**
     * The settled history has to be spread over days, not stamped with one instant.
     *
     * This is the whole point of seeding a fortnight. Every screen that reads this data reads it
     * as a history, and the date filters over the analyst's queue cannot be asked anything at all
     * by rows that share a timestamp.
     */
    @Test
    void theSettledHistoryIsSpreadAcrossDistinctDays() {
        scenario.seed();

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        List<Transfer> settled = infra.transfers.bySourceAccount(primary.id()).stream()
                .filter(t -> t.status() == TransferStatus.SENT)
                .toList();

        long days = settled.stream()
                .map(t -> t.createdAt().atZone(java.time.ZoneOffset.UTC).toLocalDate())
                .distinct()
                .count();
        assertEquals(settled.size(), days, "No two settled payments may share a date");

        Instant oldest = settled.stream().map(Transfer::createdAt).min(Instant::compareTo).orElseThrow();
        assertTrue(oldest.isBefore(Instant.now().minus(Duration.ofDays(7))),
                "The history must reach back beyond a week");
    }

    /**
     * Each settled payment is dated the seeding date less its own number of days, at whatever hour
     * of the day the seed is run.
     *
     * The test above cannot say this. It compares the rows with each other, so it only notices the
     * defect while the clock is in the window that produces it: the hour used to be subtracted
     * along with the days, which slid a row onto the day before whenever it exceeded the hour the
     * seed ran at, and a suite started after 11:00 UTC saw a fortnight with the shape it was
     * supposed to have. This restates the offsets, which is the price of an assertion that means
     * the same thing at every hour, and in exchange it fails at any hour under the arithmetic that
     * shipped rather than only in the morning.
     */
    @Test
    void everySettledPaymentIsDatedItsOwnNumberOfDaysBeforeTheSeed() {
        // Read on both sides of the seed, because a run that crosses midnight is dated by the day
        // it began on and the assertion must fail on the data rather than on the clock.
        LocalDate startedOn = LocalDate.now(ZoneOffset.UTC);
        scenario.seed();
        LocalDate finishedOn = LocalDate.now(ZoneOffset.UTC);

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        Account savings = infra.accounts.byIban(DemoScenario.SECONDARY_IBAN).orElseThrow();
        Set<LocalDate> dates = settledDates(primary, savings);

        // Every day the seeded fortnight names, and the gaps are as much of the fixture as the
        // days are: a history with a payment on each of fourteen days is a fixture nobody has.
        Set<Integer> daysBack = Set.of(1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 13);
        assertTrue(
                dates.equals(datesBefore(startedOn, daysBack))
                        || dates.equals(datesBefore(finishedOn, daysBack)),
                "The settled dates must be " + sorted(datesBefore(startedOn, daysBack))
                        + ", but were " + sorted(dates));
    }

    /** The distinct dates, in UTC, of everything the given accounts have paid out and settled. */
    private Set<LocalDate> settledDates(Account... payers) {
        return Arrays.stream(payers)
                .flatMap(payer -> infra.transfers.bySourceAccount(payer.id()).stream())
                .filter(t -> t.status() == TransferStatus.SENT)
                .map(t -> t.createdAt().atZone(ZoneOffset.UTC).toLocalDate())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<LocalDate> datesBefore(LocalDate anchor, Set<Integer> daysBack) {
        return daysBack.stream().map(anchor::minusDays).collect(Collectors.toUnmodifiableSet());
    }

    /** Both sides of the message above are sets, and a set prints in no order a reader can use. */
    private static List<LocalDate> sorted(Set<LocalDate> dates) {
        return dates.stream().sorted().toList();
    }

    /**
     * Both accounts pay, and the money moves between them in both directions.
     *
     * Seeded with one payer, the customer's own history had a single source on every line while
     * the screen above it offered to show payments from all of their accounts, and the credit leg
     * only ever ran one way. Neither is a claim a reader could check.
     */
    @Test
    void bothAccountsPayAndTheMoneyMovesBetweenThemBothWays() {
        scenario.seed();

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        Account savings = infra.accounts.byIban(DemoScenario.SECONDARY_IBAN).orElseThrow();

        assertFalse(infra.transfers.bySourceAccount(savings.id()).isEmpty(),
                "The savings account must appear as a payer, not only as a payee");
        assertTrue(receivedBy(savings, primary).gt(Money.czk(0)), "Current pays savings");
        assertTrue(receivedBy(primary, savings).gt(Money.czk(0)), "Savings pays current");

        // An intra-bank payment owes no gateway a dispatch whichever account sent it, so the one
        // paid by the savings account has to answer the same way round as the others.
        Transfer inwards = infra.transfers.bySourceAccount(savings.id()).stream()
                .filter(t -> t.targetIbanSnapshot().equals(primary.iban().value()))
                .findFirst().orElseThrow();
        assertNull(inwards.dispatchState(), "An intra-bank payment reaches no gateway");
    }

    /**
     * The queue needs a decided alert as much as an open one.
     *
     * A verdict, the analyst who reached it and the note they left are three separate fields, and
     * every screen that prints one of them was being read against a queue where no alert had any
     * of the three.
     */
    @Test
    void oneAlertIsClosedAndCarriesItsAnalystAndTheirNote() {
        scenario.seed();

        List<FraudAlert> queue = infra.alerts.all();
        assertEquals(2, queue.size());

        FraudAlert closed = queue.stream()
                .filter(a -> a.state() == FraudAlertState.OK)
                .findFirst().orElseThrow(() -> new AssertionError("No decided alert in the queue"));

        assertNotNull(closed.decidedBy(), "A decided alert names who decided it");
        assertNotNull(closed.resolvedAt(), "A decided alert records when");
        assertNotNull(closed.decisionComment(), "The verdict comment is what the notes box shows");
        assertNotNull(closed.assignee(), "A decided alert is one somebody had taken");

        // The payment behind it is withdrawn, not sent. Approving an alert clears the review, it
        // does not move the money, and the dataset must not claim otherwise.
        Transfer behind = infra.transfers.byId(closed.transferId()).orElseThrow();
        assertEquals(TransferStatus.DECLINED, behind.status());
    }

    /**
     * Both alerts carry a case journal, and the journal is not the verdict comment.
     *
     * They are two fields for two purposes and they sit in two boxes on the desk. Seeded with an
     * empty journal, the box that shows what an analyst wrote for whoever reads the alert next
     * could only ever be read empty, which says nothing about whether it works.
     */
    @Test
    void bothAlertsCarryCaseNotesAndTheJournalKeepsItsOrder() {
        scenario.seed();

        for (FraudAlert alert : infra.alerts.all()) {
            List<FraudAlertNote> journal = infra.alerts.notesOf(alert.id());
            assertFalse(journal.isEmpty(),
                    "Alert " + alert.id() + " must open with a case journal");
            journal.forEach(n -> {
                assertNotNull(n.author(), "A seeded note names who wrote it");
                assertFalse(n.text().isBlank(), "A note with no text is not a note");
                assertFalse(n.writtenAt().isBefore(alert.createdAt()),
                        "A note may not predate the alert it is filed against");
            });
        }

        FraudAlert open = infra.alerts.all().stream()
                .filter(a -> a.state() == FraudAlertState.NEW)
                .findFirst().orElseThrow();
        List<FraudAlertNote> triage = infra.alerts.notesOf(open.id());
        assertEquals(2, triage.size(), "The open alert carries the worked-on journal");
        assertTrue(triage.get(0).text().startsWith("Payee"),
                "The journal is read oldest first and must come back in the order it was written");
    }

    /**
     * The payment the code screen is for, and the point of it is the deadline rather than the row.
     *
     * Seeded with the product's five minutes it is expired before anybody opens the showcase, and
     * the screen that takes a one-time code is then correct and useless: it can only ever say that
     * the time ran out. This is what the assertion is guarding.
     */
    @Test
    void theAwaitingTransferIsStillConfirmableLongAfterItWasSeeded() {
        scenario.seed();

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        Transfer awaiting = infra.transfers.bySourceAccount(primary.id()).stream()
                .filter(t -> t.status() == TransferStatus.WAITING_AUTH)
                .findFirst().orElseThrow();

        assertNotNull(awaiting.authValidUntil(), "A waiting payment has a deadline");
        assertFalse(awaiting.isAuthExpired(), "The seeded deadline must not be in the past");
        assertTrue(awaiting.authValidUntil().isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(7))),
                "A week is the least that makes the fixture worth seeding");
    }

    @Test
    void theSecondaryAccountIsCreditedByThePaymentThatStaysInTheBank() {
        scenario.seed();

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        Account secondary = infra.accounts.byIban(DemoScenario.SECONDARY_IBAN).orElseThrow();

        // The credit leg. The amounts arrive whole: a fee is charged to the account that pays,
        // not to the one that is paid, so this sum takes no policy into account.
        Money received = receivedBy(secondary, primary);
        assertTrue(received.gt(Money.czk(0)), "Some seeded payment must stay inside the bank");

        // And this account pays too, which is what the other side of the sum is. Seeded as a payee
        // and nothing else it could not answer whether a debit here works at all.
        Money spent = charged(secondary);
        assertTrue(spent.gt(Money.czk(0)), "The savings account must pay for something");

        assertEquals(Money.czk(5_000).plus(received).minus(spent), secondary.balance(),
                "A payment to an account this bank holds must be credited in the same unit of "
                        + "work as the debit, rather than handed to the network");
    }

    @Test
    void thePaymentThatStaysInTheBankOwesTheNetworkNothing() {
        scenario.seed();

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        Account secondary = infra.accounts.byIban(DemoScenario.SECONDARY_IBAN).orElseThrow();

        // The dispatch state is what separates the two settled payments, and it is the field the
        // history screens read to say whether the money left. Reading an absent one as "stayed
        // here" is what the screens must not do: absent also means a row older than that column.
        Transfer internal = infra.transfers.bySourceAccount(primary.id()).stream()
                .filter(t -> t.status() == TransferStatus.SENT)
                .filter(t -> t.targetIbanSnapshot().equals(secondary.iban().value()))
                .findFirst().orElseThrow();
        Transfer external = infra.transfers.bySourceAccount(primary.id()).stream()
                .filter(t -> t.status() == TransferStatus.SENT)
                .filter(t -> !t.targetIbanSnapshot().equals(secondary.iban().value()))
                .findFirst().orElseThrow();

        assertNull(internal.dispatchState(), "An intra-bank payment reaches no gateway");
        assertEquals(DispatchState.PENDING, external.dispatchState(),
                "A payment that leaves the bank owes the network a dispatch");
    }

    @Test
    void seedingTwiceIsANoOpAndReturnsTheSameCustomer() {
        int first = scenario.seed();
        Account afterFirst = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();

        int second = scenario.seed();

        assertEquals(first, second, "The second seed must return the same customer id");

        Account afterSecond = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        assertEquals(afterFirst.balance(), afterSecond.balance(), "Balances must not move");
        // data() now requires the store lock; read(...) is the supported way in.
        int customers = infra.store.read(data -> data.customers.size());
        int accounts = infra.store.read(data -> data.accounts.size());
        int transfers = infra.store.read(data -> data.transfers.size());
        int alerts = infra.store.read(data -> data.fraudAlerts.size());
        int notes = infra.store.read(data -> data.fraudAlertNotes.size());

        assertEquals(1, customers);
        assertEquals(2, accounts);
        assertEquals(14, transfers);
        assertEquals(2, alerts);
        assertEquals(3, notes, "A second seed must not append the journal again");
    }

    @Test
    void seedingSurvivesAProcessRestartAgainstTheSameStore() {
        int first = scenario.seed();

        Bootstrap reopened = new Bootstrap(tempDir.resolve("data.json").toString());
        DemoScenario again = new DemoScenario(
                reopened.customers, reopened.accounts, reopened.transfers, reopened.alerts,
                reopened.uowFactory, feePolicy);

        assertEquals(first, again.seed());

        int customers = reopened.store.read(data -> data.customers.size());
        assertEquals(1, customers);
    }

    @Test
    void theOwnerOfTheDemoAccountIsResolvable() {
        int customerId = scenario.seed();

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        Customer owner = infra.customers.byAccountId(primary.id()).orElseThrow();

        assertEquals(customerId, owner.id());
    }
}
