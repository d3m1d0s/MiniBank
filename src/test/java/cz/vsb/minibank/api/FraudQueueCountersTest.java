package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AlertQueueItemDto;
import cz.vsb.minibank.api.dto.AlertQueueResponseDto;
import cz.vsb.minibank.api.dto.PageDto;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertState;
import cz.vsb.minibank.domain.SimpleFeePolicy;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.exceptions.ValidationException;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The queue counters describe the whole queue; the list beside them describes the filter and the
 * page.
 *
 * Pinned because it is a design decision that looks like a bug from the outside - seven over a
 * list of three - and the obvious "fix" makes the screen worse. Counting the filtered list would
 * leave two of the three permanently zero, since both desks open filtered to NEW, and would
 * destroy the only thing an analyst reads them for: watching SUSPICIOUS rise while they work.
 * Counting the page would be worse again: a strip reading "New: 1" over a queue holding forty.
 *
 * Run against the real JSON repositories rather than against mocks. The filters now live in the
 * store, because a page cannot be taken before them and must not be taken after the whole store
 * has been read, so a mock told what to return would assert nothing about which rows a filter
 * keeps.
 */
class FraudQueueCountersTest {

    @TempDir
    Path tempDir;

    private Bootstrap infra;
    private FraudController controller;

    private static final IBAN PAYER_IBAN = new IBAN("CZ6508000000192000145399");
    private static final String TARGET_IBAN = "CZ2001000000000012345678";

    /** The transfer under the alert that is left in NEW, so a test can withdraw that payment. */
    private int firstTransferId;

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());

        controller = new FraudController(infra.alerts, infra.transfers, infra.accounts,
                infra.customers, null, new SimpleFeePolicy(), infra.uowFactory);

        SecurityContext.setCurrentUser(new User(2, "anna.analyst", new byte[]{1}, new byte[]{2},
                UserRole.FRAUD_ANALYST, null));

        // One alert in each state, so a filter on any one of them hides the other two.
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            int accountId = infra.accounts.nextId();
            infra.accounts.save(new Account(accountId, PAYER_IBAN,
                    Money.czk(5_000_000), Money.czk(4_000_000), null));

            firstTransferId = alertedTransfer(accountId, null);
            alertedTransfer(accountId, alert -> alert.approve("anna.analyst", Instant.now()));
            alertedTransfer(accountId,
                    alert -> alert.markSuspicious("looks wrong", "anna.analyst", Instant.now()));

            scope.uow().commit();
        }
    }

    @Test
    void withNoFilterTheListAndTheCountersAgree() {
        AlertQueueResponseDto response = queue();

        assertEquals(3, response.alerts().items().size());
        assertEquals(3, response.alerts().total());
        assertEquals(1, response.counters().newCount());
        assertEquals(1, response.counters().okCount());
        assertEquals(1, response.counters().suspiciousCount());
    }

    @Test
    void aStateFilterShortensTheListAndLeavesTheCountersAlone() {
        AlertQueueResponseDto response = controller.listAlerts(
                "NEW", null, null, null, null, null, null, null, null);

        assertEquals(1, response.alerts().items().size(), "the list is the filter");
        assertEquals(1, response.alerts().total(), "and so is the total beside it");
        assertEquals(1, response.counters().newCount());
        assertEquals(1, response.counters().okCount(),
                "the counters are the queue, so the states filtered out still report themselves");
        assertEquals(1, response.counters().suspiciousCount());
    }

    @Test
    void anAmountFilterAlsoLeavesTheCountersAlone() {
        // The finding this was filed under named only the state filter. Every filter does it:
        // the counters are computed before any of them.
        AlertQueueResponseDto response = controller.listAlerts(
                null, new BigDecimal("999999"), null, null, null, null, null, null, null);

        assertEquals(0, response.alerts().items().size(), "nothing is that expensive");
        assertEquals(3, response.counters().newCount()
                        + response.counters().okCount()
                        + response.counters().suspiciousCount(),
                "an empty list does not mean an empty queue, and the counters must keep saying so");
    }

    @Test
    void aPageShorterThanTheQueueLeavesTheCountersAlone() {
        // The failure paging can introduce, and the reason the counters are a query of their own:
        // a strip that counted the rows it was handed would report one alert over a queue of three.
        AlertQueueResponseDto response = controller.listAlerts(
                null, null, null, null, null, null, null, 0, 1);

        assertEquals(1, response.alerts().items().size(), "one row was asked for");
        assertEquals(3, response.alerts().total(), "out of three the filter matched");
        assertEquals(1, response.counters().newCount());
        assertEquals(1, response.counters().okCount());
        assertEquals(1, response.counters().suspiciousCount());
    }

    @Test
    void thePagesTogetherAreTheWholeFilteredQueueAndNoRowIsRepeated() {
        List<Integer> firstPage = idsOf(controller.listAlerts(
                null, null, null, null, null, null, null, 0, 2));
        List<Integer> secondPage = idsOf(controller.listAlerts(
                null, null, null, null, null, null, null, 1, 2));

        assertEquals(2, firstPage.size());
        assertEquals(1, secondPage.size());
        assertTrue(java.util.Collections.disjoint(firstPage, secondPage),
                "offset paging over a partial order repeats a row on the next page; the id tie"
                        + " break is what stops it");
    }

    @Test
    void theQueueArrivesNewestFirst() {
        // The alerts were raised in id order, so the freshest one is the highest id. It used to
        // arrive last, which put the work an analyst wants at the bottom of their screen.
        List<Integer> ids = idsOf(queue());

        assertEquals(3, ids.size());
        assertTrue(ids.get(0) > ids.get(1) && ids.get(1) > ids.get(2),
                "expected newest first, got " + ids);
    }

    @Test
    void aPageBeyondTheEndIsEmptyAndStillReportsTheTotal() {
        AlertQueueResponseDto response = controller.listAlerts(
                null, null, null, null, null, null, null, 9, 25);

        assertEquals(0, response.alerts().items().size());
        assertEquals(3, response.alerts().total(),
                "the foot of the list still has to be able to say how many there are");
    }

    @Test
    void aPageOrSizeNoQueueCouldHaveIsRefusedRatherThanClamped() {
        // Refused for the reason every filter here is refused rather than ignored: a request
        // nobody honoured must not answer with a list that is not the one that was asked for.
        assertThrows(ValidationException.class, () -> controller.listAlerts(
                null, null, null, null, null, null, null, -1, null));
        assertThrows(ValidationException.class, () -> controller.listAlerts(
                null, null, null, null, null, null, null, null, 0));
        assertThrows(ValidationException.class, () -> controller.listAlerts(
                null, null, null, null, null, null, null, null, 101),
                "an unbounded size re-opens the very thing paging exists to close");
    }

    @Test
    void excludingATransferStatusHidesThoseAlertsAndStillCountsThem() {
        // The alert on the first transfer belongs to a payment the customer withdrew. It is the
        // analyst's to decide and nothing has decided it for them - it is simply not urgent,
        // and a queue that cannot hide it fills up with rows with nothing left to do.
        withdraw(firstTransferId);

        AlertQueueResponseDto response = controller.listAlerts(
                null, null, null, null, null, null, List.of("DECLINED"), null, null);

        assertEquals(2, response.alerts().items().size(), "the withdrawn one is out of the list");
        assertEquals(3, response.counters().newCount()
                        + response.counters().okCount()
                        + response.counters().suspiciousCount(),
                "and still in the queue, because hiding a row does not resolve an alert");
    }

    @Test
    void severalStatusesCanBeExcludedAtOnce() {
        withdraw(firstTransferId);

        AlertQueueResponseDto response = controller.listAlerts(
                null, null, null, null, null, null, List.of("DECLINED", "CREATED"), null, null);

        assertEquals(0, response.alerts().items().size(),
                "the other two are CREATED, so nothing is left to show");
    }

    @Test
    void excludingNothingHidesNothing() {
        // Both the absent parameter and an empty one. The endpoint must not hide anything of its
        // own accord: a screen may choose to, an API answering an investigator may not.
        assertEquals(3, queue().alerts().items().size());
        assertEquals(3, controller.listAlerts(
                null, null, null, null, null, null, List.of(), null, null).alerts().items().size());
        assertEquals(3, controller.listAlerts(
                null, null, null, null, null, null, List.of(" "), null, null).alerts().items().size());
    }

    @Test
    void anUnknownTransferStatusIsRefusedRatherThanIgnored() {
        // Ignoring it would answer 200 with a queue wider than the one that was asked for,
        // which tells the analyst they have seen everything when the filter never ran.
        assertThrows(ValidationException.class, () -> controller.listAlerts(
                null, null, null, null, null, null, List.of("WITHDRAWN"), null, null));
    }

    /**
     * The contradiction a fraud desk opens in, and the number that resolves it.
     *
     * One transfer status carries two meanings. A payment the customer withdrew and a payment an
     * analyst declined are both DECLINED, so a desk hiding withdrawn payments - which is right,
     * there is nothing left to decide on one - also hides every alert it has itself confirmed.
     * The screen then says two things at once: the strip above the list counts the cleared alert
     * and the list under it is empty. Both desks ship in exactly that state.
     *
     * The queue's rule is not what changes here. The hidden page is: the same query sent to the
     * other path answers with the rows that were left out, so the screen can name them instead of
     * contradicting itself.
     */
    @Test
    void whatTheQueueHidesByPaymentStatusCanBeAskedForByName() {
        int clearedTransferId = transferUnder(FraudAlertState.OK);
        withdraw(clearedTransferId);

        AlertQueueResponseDto shown = controller.listAlerts(
                "OK", null, null, null, null, null, List.of("DECLINED"), null, null);

        assertEquals(0, shown.alerts().items().size(), "the contradiction as the desk sees it");
        assertEquals(1, shown.counters().okCount(),
                "the counter above the empty list keeps saying the cleared alert exists");

        PageDto<AlertQueueItemDto> hidden = controller.listHiddenAlerts(
                "OK", null, null, null, null, null, List.of("DECLINED"), null, null);

        assertEquals(1, hidden.total(), "and this is the number that explains it");
        assertEquals(1, hidden.items().size());
        assertEquals("DECLINED", hidden.items().get(0).transferStatus(),
                "with the payment status that caused it, so the screen can say which filter did it");
        assertEquals("OK", hidden.items().get(0).state());
    }

    /**
     * The two pages are the two halves of one filter: what is shown plus what is hidden is
     * everything the filter would have matched without the exclusion, and no alert is in both.
     */
    @Test
    void theShownPageAndTheHiddenPageAddUpAndDoNotOverlap() {
        withdraw(firstTransferId);

        AlertQueueResponseDto shown = controller.listAlerts(
                null, null, null, null, null, null, List.of("DECLINED"), null, null);
        PageDto<AlertQueueItemDto> hidden = controller.listHiddenAlerts(
                null, null, null, null, null, null, List.of("DECLINED"), null, null);

        assertEquals(2, shown.alerts().total());
        assertEquals(1, hidden.total());
        assertEquals(queue().alerts().total(), shown.alerts().total() + hidden.total(),
                "together they are the queue the exclusion was applied to");

        List<Integer> shownIds = idsOf(shown);
        List<Integer> hiddenIds = hidden.items().stream().map(AlertQueueItemDto::id).toList();
        assertTrue(java.util.Collections.disjoint(shownIds, hiddenIds),
                "an alert that is on the screen is not one the screen is hiding");
    }

    /** A caller hiding nothing is told nothing is hidden, without the store being asked. */
    @Test
    void anExclusionOfNothingHidesNothing() {
        withdraw(firstTransferId);

        assertEquals(0, controller.listHiddenAlerts(
                null, null, null, null, null, null, null, null, null).total());
        assertEquals(0, controller.listHiddenAlerts(
                null, null, null, null, null, null, List.of(), null, null).total());
        assertEquals(0, controller.listHiddenAlerts(
                null, null, null, null, null, null, List.of("  "), null, null).total());
    }

    /** The hidden page pages, and refuses exactly what the queue beside it refuses. */
    @Test
    void theHiddenPageTakesTheSameParametersAndRefusesTheSameOnes() {
        withdraw(firstTransferId);

        PageDto<AlertQueueItemDto> page = controller.listHiddenAlerts(
                null, null, null, null, null, null, List.of("DECLINED", "CREATED"), 0, 1);

        assertEquals(1, page.items().size(), "one row was asked for");
        assertEquals(3, page.total(), "out of the three the inverted filter matched");

        assertThrows(ValidationException.class, () -> controller.listHiddenAlerts(
                null, null, null, null, null, null, List.of("WITHDRAWN"), null, null));
        assertThrows(ValidationException.class, () -> controller.listHiddenAlerts(
                null, new BigDecimal("900"), new BigDecimal("100"), null, null, null,
                List.of("DECLINED"), null, null));
        assertThrows(ValidationException.class, () -> controller.listHiddenAlerts(
                null, null, null, null, null, null, List.of("DECLINED"), null, 0));
    }

    // ------------------------------------------------------------------
    // fixture and plumbing
    // ------------------------------------------------------------------

    /** The payment under the one alert in the given state, for a test that needs to move it. */
    private int transferUnder(FraudAlertState state) {
        return infra.alerts.all().stream()
                .filter(a -> a.state() == state)
                .map(FraudAlert::transferId)
                .findFirst()
                .orElseThrow();
    }

    private AlertQueueResponseDto queue() {
        return controller.listAlerts(null, null, null, null, null, null, null, null, null);
    }

    private static List<Integer> idsOf(AlertQueueResponseDto response) {
        return response.alerts().items().stream()
                .map(cz.vsb.minibank.api.dto.AlertQueueItemDto::id)
                .toList();
    }

    /**
     * Files one alerted payment, applying the given verdict to the alert before it is stored.
     *
     * @return the identifier of the payment behind it
     */
    private int alertedTransfer(int accountId, java.util.function.Consumer<FraudAlert> verdict) {
        Transfer t = new Transfer(infra.transfers.nextId(), accountId, null,
                TARGET_IBAN, Money.czk(1000));
        infra.transfers.add(t);

        FraudAlert alert = new FraudAlert(infra.alerts.nextId(), t.id(),
                "New beneficiary + high amount");
        if (verdict != null) {
            verdict.accept(alert);
        }
        infra.alerts.add(alert);

        return t.id();
    }

    /** A payment the customer withdrew: the alert on it survives, the payment does not. */
    private void withdraw(int transferId) {
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            Transfer t = infra.transfers.byId(transferId).orElseThrow();
            t.decline("Canceled by customer");
            infra.transfers.save(t);
            scope.uow().commit();
        }
    }
}
