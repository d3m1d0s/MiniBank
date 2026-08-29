package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AlertDetailDto;
import cz.vsb.minibank.api.dto.HistoryItemDto;
import cz.vsb.minibank.api.dto.MoneyDto;
import cz.vsb.minibank.api.dto.PageDto;
import cz.vsb.minibank.api.dto.TransferDetailsDto;
import cz.vsb.minibank.api.dto.TransferInfoDto;
import cz.vsb.minibank.application.OwnershipGuard;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FeePolicy;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.SimpleFeePolicy;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Which side of this bank's edge a payment ended up on, as the three screens that print it are
 * told.
 *
 * The fact is not a new one. A settled payment whose beneficiary is an account here is credited
 * inside the same unit of work as the debit and reaches no gateway, and {@code Transfer.send}
 * writes {@code dispatchState = PENDING} exactly when it resolved no such account. So the answer
 * for a settled payment is already written down, and the rule that reads it is one sentence:
 *
 * <ul>
 *   <li>SENT with nothing owed to the network stayed inside the bank;</li>
 *   <li>SENT with anything owed or already handed over left it;</li>
 *   <li>anything not yet settled has no record to read, so the store is asked now.</li>
 * </ul>
 *
 * WHY THE SETTLED BRANCH MAY NOT ASK AGAIN is the whole point of the first two tests, and it is
 * why they are worth their length. Looking the beneficiary up on every read would rewrite history:
 * a payment that went out through the network would start reading as an internal one the day its
 * IBAN came to belong to this bank, and a customer opening last year's statement would be told
 * something that was never true. The stored answer is immutable by construction and the derived
 * one is fresh by construction, which is why nothing synchronises them and no observer exists.
 *
 * HONEST LIMITATION, and it is deliberate. What these tests pin is the derivation and the
 * agreement, not the spelling. The value is reached as the one component each record carries
 * beyond the ones it carried before, so renaming the wire field does not fail here. What does
 * fail: a record that never grew the field, a record that grew two, one of the three spelling it
 * differently from the other two, and any payment landing on the wrong side of the edge. A name
 * is declared on the record that carries it and a wrong one is visible the moment anybody opens
 * the screen; a wrong derivation looks exactly like a right one.
 *
 * WHAT ELSE IS PINNED HERE, and why it is not a class of its own. What a payment cost is read off
 * the same two lists by the same two controllers, and telling a charge from a quote takes a
 * payment that settled and a payment that did not, side by side, on both routes. That is the cast
 * this class already seeds, and a class of its own would seed it a second time to say something
 * about the field standing next to this one.
 *
 * The three fields these rows grew afterwards are here for the same reason. When the money moved,
 * what the payer wrote on the payment, and how far a payment leaving the bank has got are all
 * answered by the settled and the unsettled halves of one cast, and the last of them is the same
 * dispatch record the boundary rule above reads without being its answer.
 */
class BankBoundaryOnTheWireTest {

    /** The payer. Every seeded payment leaves this account. */
    private static final IBAN PAYER_IBAN = new IBAN("CZ6508000000192000145399");

    /** A beneficiary this bank holds, so a payment to it never reaches a gateway. */
    private static final IBAN IN_BANK_IBAN = new IBAN("CZ4308000000192000145407");

    /** A beneficiary this bank does not hold and never will. */
    private static final String OUTSIDE_IBAN = "CZ2001000000000012345678";

    /**
     * A beneficiary this bank does not hold at the moment the money leaves, and does hold by the
     * time anybody reads the row. This is the account that would rewrite history.
     */
    private static final IBAN LATE_ARRIVAL_IBAN = new IBAN("CZ9608000000192000145423");

    /** A fixed base, so the seeded instants are distinct by construction rather than by luck. */
    private static final Instant BASE = Instant.parse("2026-03-01T09:00:00Z");

    private static final Money AMOUNT = Money.czk(12_000);

    /** What the payer wrote on the two payments that carry a message. */
    private static final String PAYERS_OWN_REFERENCE = "Invoice 2026/114";

    /**
     * When the money on the in-bank payment actually moved: three days after it was asked for.
     *
     * Every other settled row in this fixture settles at the instant it was created, which is
     * what let a row print the creation instant under a settlement heading and look right. Pulled
     * apart here so that a mapper reading the wrong one of the two fails.
     */
    private static final Instant SENT_INSIDE_SETTLED_AT = BASE.plusSeconds(3 * 24 * 60 * 60L);

    /**
     * What the tariff of the day took, on the run that settles its payments under one policy and
     * reads them back through controllers holding another.
     *
     * Neither number is a number the shipped tariff produces for {@link #AMOUNT}, which is 145.00.
     * Two arbitrary values, far apart, because the point is only that the row prints the one that
     * was taken and never the one that would be taken now.
     */
    private static final Money CHARGED_WHEN_IT_SETTLED = Money.czk(7.00);

    /** What a later tariff quotes for the same payment, and what no history row may print. */
    private static final Money QUOTED_BY_A_LATER_TARIFF = Money.czk(999.00);

    /**
     * The components each record carried before this fact reached the wire.
     *
     * Named rather than counted, so that a second field added beside the boundary is reported as
     * an ambiguity instead of being silently read as the boundary itself.
     *
     * A component the record grows for some other reason belongs on this list too, and
     * {@code fee} is the first one to arrive. What these tests reach for is the single component
     * not accounted for here, so a new field left off is read as a second candidate for the route
     * and fails every test in this class instead of the one it is about.
     */
    private static final Set<String> HISTORY_ITEM_FIELDS = Set.of(
            "id", "createdAt", "settledAt", "amount", "fee", "status", "fromIban", "toIban",
            "message", "declineReason");

    // dispatchState is listed for the reason it is listed on the detail below: what these tests
    // reach for is the ONE component not accounted for, so leaving it off would offer it as a
    // second candidate for the route. It is not the route and must never be read as one.
    //
    // declineReason joined the same way and is listed for the same reason. It is the sentence the
    // bank wrote about the payment, which the other two records already carried and this one did
    // not, so the analyst deciding an alert could not read why the payment under review had been
    // refused. Adding it made this class fail twice with "grew more than one field", which is the
    // list above doing its job: it cannot tell a new fact from the route by looking, so it refuses
    // to guess. Naming it here is the whole of the fix.
    private static final Set<String> TRANSFER_INFO_FIELDS = Set.of(
            "id", "code", "status", "fromIban", "fromBalance", "toIban", "dispatchState", "amount",
            "feeAmount", "createdAt", "settledAt", "message", "declineReason", "authMethod");

    // dispatchState is the second component this record has grown for a reason of its own, and it
    // is listed here for the reason fee is listed above: what these tests reach for is the ONE
    // component not accounted for, so a field left off would be read as a second candidate for the
    // route. It is not the route and must never be read as one - it says how far a payment that is
    // leaving has got, and null on it covers three situations, which is exactly why isToIbanInBank
    // exists.
    private static final Set<String> TRANSFER_DETAILS_FIELDS = Set.of(
            "id", "fromIban", "fromBalance", "toIban", "amount", "feeAmount", "status",
            "createdAt", "settledAt", "dispatchState", "message", "declineReason", "authMethod",
            "triesLeft", "authValidUntil");

    @TempDir
    Path tempDir;

    private Bootstrap infra;
    private FraudController fraudController;
    private AuthorizationController authorizationController;

    private int payerCustomerId;
    private int alertOnHeldToOutside;
    private int alertOnSentInside;
    private int alertOnSentOutside;

    /** Transfer ids of the nine seeded cases, by the name each case is argued under. */
    private final Map<String, Integer> seeded = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());
        FeePolicy feePolicy = new SimpleFeePolicy();

        // Null for the two services neither read path calls. Both controllers reach the store
        // through repositories on these endpoints, and a real TransferApplicationService here
        // would register the audit observers of a write path this class never takes.
        fraudController = new FraudController(infra.alerts, infra.transfers, infra.accounts,
                infra.customers, null, feePolicy, infra.uowFactory);
        authorizationController = new AuthorizationController(null, infra.accounts,
                infra.transfers, feePolicy,
                new OwnershipGuard(infra.customers, infra.accounts), infra.uowFactory);

        seed(feePolicy);
    }

    /**
     * A payment that recorded an obligation to the network is routed by that record, and one that
     * recorded nothing is routed by asking the store now.
     *
     * The two cases that make this more than a restatement are the last two, and they pull in
     * opposite directions. One left the bank before an account with its beneficiary's number
     * existed here, and the store would now answer that the number is ours: it carries a dispatch
     * state, so it still reads as having left, and no lookup is made. Re-deriving that one would
     * restate a past that did not happen.
     *
     * The other carries nothing, and the mistake worth a test is reading that as the opposite
     * answer. An absent dispatch state holds three situations, which {@code DispatchState} sets
     * out: a payment credited inside the bank, one that has not settled, and every row written
     * before that column existed. Silence is not an answer, so the store is asked, and this row's
     * beneficiary is not an account here.
     */
    @Test
    void aSettledPaymentIsRoutedByWhatWasWrittenWhenItSettled() {
        Map<Integer, HistoryItemDto> rows = deskHistoryById();

        Object inside = boundaryOf(row(rows, "sentInside"), HISTORY_ITEM_FIELDS);
        Object outside = boundaryOf(row(rows, "sentOutside"), HISTORY_ITEM_FIELDS);

        assertNotNull(inside, "a payment that stayed in the bank must say so, not say nothing");
        assertNotNull(outside, "a payment that left the bank must say so, not say nothing");
        assertNotEquals(inside, outside,
                "a payment credited inside this bank and one handed to the network must not"
                        + " reach the screen reading alike");

        assertEquals(outside, boundaryOf(row(rows, "dispatchedOutside"), HISTORY_ITEM_FIELDS),
                "a payment already handed to a gateway has left the bank just as surely as one"
                        + " still owed to it: PENDING and DISPATCHED are two stages of the same"
                        + " journey out");

        assertEquals(outside, boundaryOf(row(rows, "sentBeforeItsIbanCameToUs"),
                        HISTORY_ITEM_FIELDS),
                "this payment went out through the network, and an account holding its"
                        + " beneficiary's number was opened here afterwards; re-deriving the"
                        + " route on read would restate a past that did not happen");

        assertEquals(outside, boundaryOf(row(rows, "settledBeforeDispatchWasRecorded"),
                        HISTORY_ITEM_FIELDS),
                "this row was written before the dispatch column existed, so its empty dispatch"
                        + " state is silence rather than an answer, and the live store is asked"
                        + " instead; its beneficiary is not an account here, so it left. Reading"
                        + " that silence as 'stayed here' put two of the ten payments in the"
                        + " demonstration database on the screen as internal, one of them paying"
                        + " a different bank entirely");
    }

    /**
     * A payment that has not settled has nothing written down, so the store is asked now.
     *
     * Held and declined payments are the bulk of what an analyst reads: the history beside an
     * alert is a person's recent behaviour, and the whole reason to look at it is that some of it
     * was stopped. A route that went blank on everything but SENT would be missing from most of
     * the table.
     */
    @Test
    void aPaymentThatHasNotSettledIsRoutedByAskingTheStoreNow() {
        Map<Integer, HistoryItemDto> rows = deskHistoryById();

        Object inside = boundaryOf(row(rows, "sentInside"), HISTORY_ITEM_FIELDS);
        Object outside = boundaryOf(row(rows, "sentOutside"), HISTORY_ITEM_FIELDS);

        assertEquals(inside, boundaryOf(row(rows, "heldToInside"), HISTORY_ITEM_FIELDS),
                "a held payment to an account of this bank would be credited here if it were"
                        + " released, and the same two words have to say so");
        assertEquals(outside, boundaryOf(row(rows, "heldToOutside"), HISTORY_ITEM_FIELDS),
                "a held payment to an account elsewhere would leave the bank if it were"
                        + " released");
        assertEquals(inside, boundaryOf(row(rows, "declinedToInside"), HISTORY_ITEM_FIELDS),
                "a refusal does not make the beneficiary somebody else's customer");
        assertEquals(outside, boundaryOf(row(rows, "declinedToOutside"), HISTORY_ITEM_FIELDS),
                "nor does it make them ours");
    }

    /**
     * The customer's own history answers the same route as the analyst's, row by row.
     *
     * Two endpoints in two controllers build this record from the same transfers, and they have
     * already drifted once: the fraud desk could read a decline reason its owner could not. The
     * cheapest guard against the next drift is to read both lists in one test and compare them
     * rather than to state the expected value twice.
     */
    @Test
    void theCustomersOwnHistoryAndTheAnalystsAgreeOnEveryRow() {
        Map<Integer, HistoryItemDto> desk = deskHistoryById();
        Map<Integer, HistoryItemDto> mine = customerHistoryById();

        assertEquals(desk.keySet(), mine.keySet(),
                "both lists cover every payment this customer sent, so they are about the same"
                        + " rows");

        for (Map.Entry<Integer, HistoryItemDto> entry : desk.entrySet()) {
            assertEquals(boundaryOf(entry.getValue(), HISTORY_ITEM_FIELDS),
                    boundaryOf(mine.get(entry.getKey()), HISTORY_ITEM_FIELDS),
                    "payment " + entry.getKey() + " must be on the same side of the bank on the"
                            + " analyst's screen and on its owner's");
        }
    }

    /**
     * The transfer detail carries the fact too, under the same two words as the history rows.
     *
     * The detail is the one screen a payment reaches on its own, without a list around it to
     * compare against, so a route missing there is missing exactly where there is nothing else to
     * infer it from. Asserted against the history vocabulary rather than against a literal, so
     * that a detail screen saying the same thing in different words fails here.
     */
    @Test
    void theTransferDetailCarriesTheSameFactInTheSameWords() {
        Map<Integer, HistoryItemDto> rows = deskHistoryById();
        Object inside = boundaryOf(row(rows, "sentInside"), HISTORY_ITEM_FIELDS);
        Object outside = boundaryOf(row(rows, "sentOutside"), HISTORY_ITEM_FIELDS);

        assertEquals(inside, boundaryOf(details("sentInside"), TRANSFER_DETAILS_FIELDS),
                "the detail of a payment credited inside this bank");
        assertEquals(outside, boundaryOf(details("sentOutside"), TRANSFER_DETAILS_FIELDS),
                "the detail of a payment handed to the network");
        assertEquals(outside, boundaryOf(details("sentBeforeItsIbanCameToUs"),
                        TRANSFER_DETAILS_FIELDS),
                "the detail reads the record for a settled payment for the same reason the list"
                        + " does");
        assertEquals(outside, boundaryOf(details("heldToOutside"), TRANSFER_DETAILS_FIELDS),
                "and asks the store for one that has not settled");
    }

    /**
     * The alert screen's own transfer panel says it as well.
     *
     * That panel is the payment the alert was raised on, printed above the history of everything
     * else the customer sent. Without the fact there, the one payment an analyst is deciding
     * about would be the only one on the screen not saying where it was going.
     */
    @Test
    void theAlertedTransferSaysItTooAndAgreesWithItsOwnHistoryRow() {
        asAnalyst();

        AlertDetailDto heldCase = fraudController.getAlert(alertOnHeldToOutside);
        AlertDetailDto sentCase = fraudController.getAlert(alertOnSentInside);

        Object outside = boundaryOf(row(byId(heldCase.history()), "heldToOutside"),
                HISTORY_ITEM_FIELDS);
        Object inside = boundaryOf(row(byId(sentCase.history()), "sentInside"),
                HISTORY_ITEM_FIELDS);

        assertEquals(outside, boundaryOf(heldCase.transfer(), TRANSFER_INFO_FIELDS),
                "the alerted payment is heading out of the bank, and its own row in the table"
                        + " below already says so");
        assertEquals(inside, boundaryOf(sentCase.transfer(), TRANSFER_INFO_FIELDS),
                "and an alerted payment that settled inside the bank reads inside in both"
                        + " places");
    }

    /**
     * One fact, one name, on all three records.
     *
     * The three are built in two controllers by three separate mappers, and the field list the
     * desks share is a union of names: a second spelling would not fail any build, it would leave
     * one of the three screens quietly printing nothing while the other two print the route. This
     * is the half of the contract the value assertions above cannot see, since they compare
     * whatever each record happens to carry.
     */
    @Test
    void theThreeRecordsCallTheFactByOneName() {
        String onAHistoryRow =
                boundaryComponent(HistoryItemDto.class, HISTORY_ITEM_FIELDS).getName();

        assertEquals(onAHistoryRow,
                boundaryComponent(TransferInfoDto.class, TRANSFER_INFO_FIELDS).getName(),
                "the payment an alert was raised on and the payments beneath it in the same"
                        + " panel must name their route the same way");
        assertEquals(onAHistoryRow,
                boundaryComponent(TransferDetailsDto.class, TRANSFER_DETAILS_FIELDS).getName(),
                "and so must the detail the customer opens from the row");
    }

    // -------------------------------------------------------------------------
    // What the payment cost, on the same two routes
    // -------------------------------------------------------------------------

    /**
     * A history row carries the fee that was taken, and not the fee today's tariff would take.
     *
     * The two numbers are the same on every row of the demonstration database, so a mapper built
     * from the wrong one looks right on the screen. Here they are pulled apart: the payments are
     * settled under one policy and read back through controllers holding another, and only the
     * stored charge survives that.
     *
     * {@code Transfer} offers three fees and this is the test that tells them apart on a settled
     * row. {@code feeAmount} is a bare quote and answers the later tariff everywhere, settled or
     * not, so it fails here. {@code feeFor} falls back to a quote only where nothing was charged,
     * so it agrees with the record on exactly these two rows and passes, which is the point: the
     * wire carries {@code feeFor}, and what makes that safe is that a charge always outranks the
     * fallback. A tariff changed after a payment settled may not reach back and reprice it.
     *
     * Both routes, because two controllers build this record with two separate mappers, and the
     * pair has drifted before.
     */
    @Test
    void theFeeOnAHistoryRowIsWhatWasChargedAndNotWhatWouldBeChargedNow() {
        rebuiltOnFees(CHARGED_WHEN_IT_SETTLED, QUOTED_BY_A_LATER_TARIFF);

        Map<Integer, HistoryItemDto> desk = deskHistoryById();
        Map<Integer, HistoryItemDto> mine = customerHistoryById();

        for (String settled : List.of("sentInside", "sentOutside")) {
            MoneyDto charged = row(desk, settled).fee();

            assertNotNull(charged, "the " + settled + " payment settled and was charged, so its"
                    + " row has a fee to print");
            assertEquals(CHARGED_WHEN_IT_SETTLED.amount().toPlainString(), charged.amount(),
                    "the " + settled + " payment was charged this when the money moved; the"
                            + " tariff the controllers hold now would say "
                            + QUOTED_BY_A_LATER_TARIFF.amount().toPlainString());
            assertEquals("CZK", charged.currency(),
                    "a fee stands directly under an amount that names its currency, and the two"
                            + " were printed with different units the last time one of them"
                            + " forgot");
            assertEquals(charged, row(mine, settled).fee(),
                    "the customer and the analyst are looking at the same payment, so they are"
                            + " owed the same answer to what it cost");
        }
    }

    /**
     * A payment that has not settled is priced by the tariff, so its row carries a fee like every
     * other row.
     *
     * This is the row where {@code fee} and {@code feeFor} part company, and it is the one that
     * catches the first of them. Held and declined payments are most of what an analyst reads, and
     * while the wire carried {@code fee} alone their rows arrived empty: the fee column was blank
     * on the majority of the table, which reads as a table that lost its numbers rather than as an
     * answer. The controllers here hold the shipped tariff, which quotes 145.00 for each of these
     * amounts, and 145.00 is what a reader is owed - what the payment would cost.
     *
     * What this number is NOT is money anybody took, and nothing in this field says which of the
     * two it is. The status in the next cell is what says it, which is why it is asserted here in
     * the same breath: a fee printed beside {@code DECLINED} or {@code HELD_FOR_REVIEW} is a
     * price, and the day a screen prints this field without that word beside it, it is printing a
     * quote as a receipt.
     */
    @Test
    void aPaymentThatHasNotSettledIsPricedByTheTariffRatherThanLeftBlank() {
        Map<Integer, HistoryItemDto> desk = deskHistoryById();
        Map<Integer, HistoryItemDto> mine = customerHistoryById();

        String quoted = new SimpleFeePolicy().compute(AMOUNT).amount().toPlainString();

        for (String unsettled : List.of("heldToInside", "heldToOutside", "declinedToOutside")) {
            MoneyDto fee = row(desk, unsettled).fee();

            assertNotNull(fee, "the " + unsettled + " payment has a price whether or not it was"
                    + " ever taken, and a row that leaves the fee out is one the reader cannot"
                    + " tell from a row whose fee went missing");
            assertEquals(quoted, fee.amount(),
                    "and the price is this tariff's answer for the amount, which is the only"
                            + " honest one available on a payment nothing was taken on");
            assertEquals(fee, row(mine, unsettled).fee(),
                    "the customer and the analyst are looking at the same payment, so they are"
                            + " owed the same answer to what it costs");
            assertNotEquals("SENT", row(desk, unsettled).status(),
                    "this number is a price and not a receipt, and the status beside it is the"
                            + " whole of what tells a reader which one they are looking at");
        }
    }

    /**
     * A payment that was charged nothing is not the same row as one that has not been charged yet,
     * and the two are told apart by their numbers rather than by one of them being blank.
     *
     * This is what the field gave up its null for. A free payment answers 0.00 because that is
     * what it cost; a held payment answers the tariff because that is what it would cost, and the
     * fixture pulls the two numbers far apart on purpose. They are asserted against each other
     * rather than apart because that is how a reader meets them: the free payment and the held one
     * are rows of the same table, read down in one go, and neither may be the one with a hole in
     * it.
     *
     * The customer is asking what the payment cost them. 0.00 answers it; a blank asked them to
     * know the tariff before they could tell a free payment from one whose fee the screen dropped.
     */
    @Test
    void aFeeOfZeroIsAnAnswerAndTheHeldRowBesideItCarriesTheTariffInstead() {
        rebuiltOnFees(Money.czk(0.00), QUOTED_BY_A_LATER_TARIFF);

        Map<Integer, HistoryItemDto> desk = deskHistoryById();
        Map<Integer, HistoryItemDto> mine = customerHistoryById();

        MoneyDto free = row(desk, "sentInside").fee();

        assertNotNull(free, "this payment settled under a tariff that charges nothing, and being"
                + " charged nothing is an answer the row has to give");
        assertEquals("0.00", free.amount(),
                "and it gives it as an amount, at the scale every other money value on this wire"
                        + " is written in");
        assertEquals("CZK", free.currency(), "a charge of zero is still money, in the one"
                + " currency this bank charges in");
        assertEquals(free, row(mine, "sentInside").fee(),
                "on both routes, as with any other charge");

        MoneyDto held = row(desk, "heldToInside").fee();

        assertNotNull(held, "and the held payment further up the same table is priced too, or the"
                + " column goes blank on exactly the rows a desk exists to read");
        assertEquals(QUOTED_BY_A_LATER_TARIFF.amount().toPlainString(), held.amount(),
                "nothing was taken on it, so the tariff is what its row can honestly print");
        assertNotEquals(free.amount(), held.amount(),
                "the free payment and the held one must not reach the screen reading alike: one"
                        + " cost nothing and the other has not been charged yet, and after the"
                        + " null went it is the number that has to carry that difference");
    }

    // -------------------------------------------------------------------------
    // What else a row of this table now says
    // -------------------------------------------------------------------------

    /**
     * A row says when the money moved, which is not the same question as when it was asked for.
     *
     * The row carried one instant and both desks printed it under a heading about the payment,
     * so a payment submitted on the first and sent on the fourth read as a payment of the first
     * on every screen there is. The fixture settles this one three days after it was created
     * precisely so that a mapper reading the wrong field of the two cannot pass here: everywhere
     * else in this seed the two instants are equal, which is how the gap survived being looked at.
     *
     * Null on anything that has not settled, and that is the honest answer rather than a hole:
     * there is no instant to print, and the shared formatter draws the absence as a dash.
     */
    @Test
    void aRowSaysWhenTheMoneyMovedAndSaysNothingWhereItHasNotYet() {
        Map<Integer, HistoryItemDto> desk = deskHistoryById();
        Map<Integer, HistoryItemDto> mine = customerHistoryById();

        HistoryItemDto settled = row(desk, "sentInside");

        assertEquals(SENT_INSIDE_SETTLED_AT.toString(), settled.settledAt(),
                "the money moved three days after the payment was asked for, and that is the"
                        + " instant a settlement heading stands over");
        assertNotEquals(settled.createdAt(), settled.settledAt(),
                "the two instants answer different questions, and a row that prints the created"
                        + " one under both is a row that cannot show how long a payment sat");
        assertEquals(settled.settledAt(), row(mine, "sentInside").settledAt(),
                "the customer and the analyst are looking at the same payment");

        for (String unsettled : List.of("heldToOutside", "declinedToOutside")) {
            assertNull(row(desk, unsettled).settledAt(),
                    "nothing has moved on the " + unsettled + " payment, so there is no instant"
                            + " to print and inventing one would date a settlement that never"
                            + " happened");
            assertNull(row(mine, unsettled).settledAt(), "on both routes");
        }
    }

    /**
     * A row carries the payer's own reference for the payment.
     *
     * The one field on this record whose absence was asymmetric in the worst direction: a customer
     * could write a message on the payment form and had no screen anywhere that read it back to
     * them, so the only sentence they ever attached to their own payment was write-only. On the
     * desk it is what tells an analyst what a payment was said to be for, which is most of what
     * makes an amount look in or out of character.
     *
     * Both readings are in the fixture. A payment the payer wrote nothing on carries null, which
     * is a different fact from an empty string and is why the two rows are asserted together.
     */
    @Test
    void aRowCarriesWhatThePayerWroteOnTheirOwnPayment() {
        Map<Integer, HistoryItemDto> desk = deskHistoryById();
        Map<Integer, HistoryItemDto> mine = customerHistoryById();

        assertEquals(PAYERS_OWN_REFERENCE, row(desk, "sentInside").message(),
                "the payer typed this into the payment form, and until now nothing read it back");
        assertEquals(PAYERS_OWN_REFERENCE, row(mine, "sentInside").message(),
                "least of all the person who wrote it");
        assertEquals(PAYERS_OWN_REFERENCE, row(desk, "heldToOutside").message(),
                "a payment that has not settled carries its message too: the desk reading it is"
                        + " deciding about exactly those");

        assertNull(row(desk, "sentOutside").message(),
                "nothing was written on this one, and absent is a different fact from empty");
        assertNull(row(mine, "sentOutside").message(), "on both routes");
    }

    /**
     * The ALERTED payment carries the payer's reference and its settlement instant too, and not
     * only the rows in the table beneath it.
     *
     * This panel is the one an analyst is deciding on, and it was the last record on this API to
     * carry neither. The absence was invisible on screen for the worst possible reason: the same
     * payment appears again in the history table under the panel, where both fields have been
     * printed all along, so the desk looked complete while the record it is built from was not.
     *
     * The message is the sharp half. It is counted against 140 characters on the way in and
     * stored, and it is often the whole of what the payer said about the payment, so an analyst
     * was being asked to judge a payment with the payer's own description of it hidden. The
     * settlement instant is the other: on a desk whose whole list is held and settled payments,
     * "when it was asked for" and "when the money moved" are days apart, and one heading standing
     * over both cannot say how long anything sat.
     *
     * Two alerts, because the two fields have two readings each and one payment cannot show both:
     * the held payment has a message and no settlement, the settled one has both.
     */
    @Test
    void theAlertedPaymentCarriesThePayersReferenceAndWhenTheMoneyMoved() {
        asAnalyst();
        TransferInfoDto held = fraudController.getAlert(alertOnHeldToOutside).transfer();
        TransferInfoDto settled = fraudController.getAlert(alertOnSentInside).transfer();

        assertEquals(PAYERS_OWN_REFERENCE, held.message(),
                "the panel an analyst decides from must show what the payer wrote on the payment");
        assertNull(held.settledAt(),
                "and nothing has moved on a held payment, so there is no instant to print");

        assertEquals(PAYERS_OWN_REFERENCE, settled.message());
        assertEquals(SENT_INSIDE_SETTLED_AT.toString(), settled.settledAt(),
                "the money moved three days after this payment was asked for");
        assertNotEquals(settled.createdAt(), settled.settledAt(),
                "the two instants answer different questions, and a panel printing the created"
                        + " one under both cannot show how long the payment sat");
    }

    /**
     * The alerted payment says how far it has got on the way out of the bank.
     *
     * This is the one payment an analyst is deciding about, and the decision turns on the answer:
     * a payment still owed to the network is one a DECLINE can still stop, and one already handed
     * over is a case file. The panel could say whether the money was leaving and not whether it
     * had left, and those are two questions.
     *
     * Beside {@code toIbanInBank} and not instead of it. Null is asserted here on both a payment
     * credited inside this bank and a payment that has not settled at all, which is the whole of
     * why the two fields cannot be derived from each other: the same null stands over an
     * intra-bank payment, an unsettled one, and a row written before the column existed.
     */
    @Test
    void theAlertedPaymentSaysHowFarItHasGotOnTheWayOut() {
        asAnalyst();

        assertEquals("PENDING",
                fraudController.getAlert(alertOnSentOutside).transfer().dispatchState(),
                "the money has left this bank and the network has not been handed it yet, which"
                        + " is the state an analyst can still act on");
        assertNull(fraudController.getAlert(alertOnSentInside).transfer().dispatchState(),
                "a payment credited inside this bank owes the network nothing, and silence is"
                        + " what that reads as");
        assertNull(fraudController.getAlert(alertOnHeldToOutside).transfer().dispatchState(),
                "and a payment that has not settled has nothing written down at all, which is"
                        + " the same silence over a different situation - the reason this field"
                        + " may never be read as the answer to which side of the bank it was"
                        + " going");
    }

    // -------------------------------------------------------------------------
    // Reading the surfaces
    // -------------------------------------------------------------------------

    private Map<Integer, HistoryItemDto> deskHistoryById() {
        asAnalyst();
        return byId(fraudController.getAlert(alertOnHeldToOutside).history());
    }

    private Map<Integer, HistoryItemDto> customerHistoryById() {
        asCustomer();
        PageDto<HistoryItemDto> page = authorizationController.listMyTransfers(null, null);
        return byId(page.items());
    }

    private TransferDetailsDto details(String caseName) {
        asCustomer();
        return authorizationController.transferDetails(idOf(caseName));
    }

    private static Map<Integer, HistoryItemDto> byId(List<HistoryItemDto> rows) {
        Map<Integer, HistoryItemDto> byId = new HashMap<>();
        for (HistoryItemDto row : rows) {
            byId.put(row.id(), row);
        }
        return byId;
    }

    private HistoryItemDto row(Map<Integer, HistoryItemDto> rows, String caseName) {
        HistoryItemDto row = rows.get(idOf(caseName));
        if (row == null) {
            fail("The history does not carry the " + caseName + " payment at all, so nothing"
                    + " about its route can be read. Rows present: " + rows.keySet());
        }
        return row;
    }

    private int idOf(String caseName) {
        Integer id = seeded.get(caseName);
        if (id == null) {
            throw new IllegalArgumentException("No seeded case named " + caseName);
        }
        return id;
    }

    /**
     * The one component this record carries beyond the ones it carried before.
     *
     * Fails loudly on both ways of getting it wrong: a record that grew nothing never reached the
     * screen with the fact, and a record that grew two leaves the reader guessing which one the
     * screens are printing.
     */
    private static RecordComponent boundaryComponent(Class<?> record, Set<String> carriedBefore) {
        List<RecordComponent> added = Arrays.stream(record.getRecordComponents())
                .filter(c -> !carriedBefore.contains(c.getName()))
                .toList();

        if (added.isEmpty()) {
            fail(record.getSimpleName() + " carries nothing beyond " + carriedBefore
                    + ", so no screen built from it can say whether the payment stayed in this"
                    + " bank or left it");
        }
        if (added.size() > 1) {
            fail(record.getSimpleName() + " grew more than one field: "
                    + added.stream().map(RecordComponent::getName).toList()
                    + ". Which of them is the route cannot be told from here.");
        }

        return added.get(0);
    }

    /** What that component holds for one response. */
    private static Object boundaryOf(Object dto, Set<String> carriedBefore) {
        RecordComponent boundary = boundaryComponent(dto.getClass(), carriedBefore);
        try {
            return boundary.getAccessor().invoke(dto);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("Could not read "
                    + dto.getClass().getSimpleName() + "." + boundary.getName(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Seeding
    // -------------------------------------------------------------------------

    /**
     * Builds the whole fixture again on an empty store, with the payments charged by one tariff
     * and the controllers that read them back holding another.
     *
     * A second store rather than more payments in the first one. {@link #seed} writes a fixed
     * cast of nine and the panel shows the newest ten, so seeding a second cast beside the first
     * would push half of it off the end of the table these tests read.
     *
     * @param charged   what every payment that settles here is charged
     * @param quotedNow what the controllers would charge for the same payment today, which is the
     *                  number a row reading the tariff instead of the record would print
     */
    private void rebuiltOnFees(Money charged, Money quotedNow) {
        infra = new Bootstrap(tempDir.resolve("recharged.json").toString());
        fraudController = new FraudController(infra.alerts, infra.transfers, infra.accounts,
                infra.customers, null, amount -> quotedNow, infra.uowFactory);
        authorizationController = new AuthorizationController(null, infra.accounts,
                infra.transfers, amount -> quotedNow,
                new OwnershipGuard(infra.customers, infra.accounts), infra.uowFactory);

        seeded.clear();
        seed(amount -> charged);
    }

    private void asAnalyst() {
        SecurityContext.setCurrentUser(new User(2, "anna.analyst", new byte[]{1}, new byte[]{2},
                UserRole.FRAUD_ANALYST, null));
    }

    private void asCustomer() {
        SecurityContext.setCurrentUser(new User(1, "boundary.payer", new byte[]{1}, new byte[]{2},
                UserRole.CUSTOMER, payerCustomerId));
    }

    /**
     * One payer, nine payments covering every branch of the rule, and two alerts.
     *
     * Written through the repositories and settled through {@code Transfer.send}, so what a row
     * owes the network is decided by the domain rather than stated by this test. The one
     * exception is the pre-column row, which no live call path can produce any more and which is
     * therefore hydrated the way the mappers hydrate a stored one.
     *
     * The account that arrives late is opened in a second unit of work, after the payment to it
     * has already gone out. That order is the test: it is the only way to have a settled payment
     * whose stored route and whose live route disagree.
     */
    private void seed(FeePolicy feePolicy) {
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            payerCustomerId = infra.customers.nextId();
            Customer payer = new Customer(payerCustomerId, "Boundary Probe",
                    "boundary@example.com", new Address("Hlavni 1", "Ostrava"),
                    Money.czk(4_000_000));
            infra.customers.save(payer);

            int payerAccountId = infra.accounts.nextId();
            Account payerAccount = new Account(payerAccountId, PAYER_IBAN,
                    Money.czk(5_000_000));
            infra.accounts.save(payerAccount);
            payer.addAccountId(payerAccountId);
            infra.customers.save(payer);

            int neighbourCustomerId = infra.customers.nextId();
            Customer neighbour = new Customer(neighbourCustomerId, "Neighbour",
                    "neighbour@example.com", new Address("Hlavni 2", "Ostrava"),
                    Money.czk(4_000_000));
            infra.customers.save(neighbour);

            int inBankAccountId = infra.accounts.nextId();
            Account inBankAccount = new Account(inBankAccountId, IN_BANK_IBAN,
                    Money.czk(1_000));
            infra.accounts.save(inBankAccount);
            neighbour.addAccountId(inBankAccountId);
            infra.customers.save(neighbour);

            // SENT, credited here: send resolves a destination, so nothing is owed to the
            // network and nothing is written about a dispatch.
            Transfer sentInside = created(payerAccountId, IN_BANK_IBAN.value(), 0);
            // The payer's own reference. On this one and on the held payment below, and on
            // neither of the others, so that both readings of the field are in the fixture: a
            // payment carries what its payer wrote, and a payment written nothing carries null.
            sentInside.attachMessage(PAYERS_OWN_REFERENCE);
            sentInside.send(payerAccount, inBankAccount, feePolicy, SENT_INSIDE_SETTLED_AT);
            record("sentInside", sentInside);

            // SENT, out through the network and still owed to it.
            Transfer sentOutside = created(payerAccountId, OUTSIDE_IBAN, 1);
            sentOutside.send(payerAccount, null, feePolicy, BASE.plusSeconds(60));
            record("sentOutside", sentOutside);

            // SENT, out through the network and already handed over.
            Transfer dispatchedOutside = created(payerAccountId, OUTSIDE_IBAN, 2);
            dispatchedOutside.send(payerAccount, null, feePolicy, BASE.plusSeconds(120));
            dispatchedOutside.markDispatched();
            record("dispatchedOutside", dispatchedOutside);

            // SENT, out through the network to a number this bank does not hold yet.
            Transfer lateArrival = created(payerAccountId, LATE_ARRIVAL_IBAN.value(), 3);
            lateArrival.send(payerAccount, null, feePolicy, BASE.plusSeconds(180));
            record("sentBeforeItsIbanCameToUs", lateArrival);

            // SENT by a bank that did not yet record what it owed the network. Hydrated, not
            // sent: send would write PENDING, which is the very thing this row lacks.
            Transfer beforeTheColumn = new Transfer(infra.transfers.nextId(), payerAccountId,
                    null, OUTSIDE_IBAN, AMOUNT, BASE.plusSeconds(240));
            beforeTheColumn.hydrateForLoad(TransferStatus.SENT, null, null,
                    BASE.plusSeconds(240));
            beforeTheColumn.hydrateSettlement(Money.czk(50), BASE.plusSeconds(240));
            infra.transfers.add(beforeTheColumn);
            seeded.put("settledBeforeDispatchWasRecorded", beforeTheColumn.id());

            Transfer heldToInside = created(payerAccountId, IN_BANK_IBAN.value(), 5);
            heldToInside.holdForReview(null);
            record("heldToInside", heldToInside);

            Transfer heldToOutside = created(payerAccountId, OUTSIDE_IBAN, 6);
            heldToOutside.attachMessage(PAYERS_OWN_REFERENCE);
            heldToOutside.holdForReview(null);
            record("heldToOutside", heldToOutside);

            Transfer declinedToInside = created(payerAccountId, IN_BANK_IBAN.value(), 7);
            declinedToInside.decline("Refused by the analyst");
            record("declinedToInside", declinedToInside);

            Transfer declinedToOutside = created(payerAccountId, OUTSIDE_IBAN, 8);
            declinedToOutside.decline("Refused by the analyst");
            record("declinedToOutside", declinedToOutside);

            infra.accounts.save(payerAccount);
            infra.accounts.save(inBankAccount);

            alertOnHeldToOutside = infra.alerts.nextId();
            infra.alerts.add(new FraudAlert(alertOnHeldToOutside, heldToOutside.id(),
                    "New beneficiary + high amount"));

            alertOnSentInside = infra.alerts.nextId();
            infra.alerts.add(new FraudAlert(alertOnSentInside, sentInside.id(),
                    "New beneficiary + high amount"));

            // The third alert exists so that the alerted-payment panel can be read on a payment
            // that owes the network something. On the other two the dispatch state is null, and a
            // panel asserted only against nulls would pass while carrying nothing.
            alertOnSentOutside = infra.alerts.nextId();
            infra.alerts.add(new FraudAlert(alertOnSentOutside, sentOutside.id(),
                    "New beneficiary + high amount"));

            scope.uow().commit();
        }

        // The account that turns up afterwards. Nothing about the payment that already left for
        // its number changes, and that is what the first test asserts.
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            int lateCustomerId = infra.customers.nextId();
            Customer late = new Customer(lateCustomerId, "Late Arrival",
                    "late@example.com", new Address("Hlavni 3", "Ostrava"),
                    Money.czk(4_000_000));
            infra.customers.save(late);

            int lateAccountId = infra.accounts.nextId();
            infra.accounts.save(new Account(lateAccountId, LATE_ARRIVAL_IBAN,
                    Money.czk(1_000)));
            late.addAccountId(lateAccountId);
            infra.customers.save(late);

            scope.uow().commit();
        }
    }

    private Transfer created(int sourceAccountId, String targetIban, int minutesFromBase) {
        return new Transfer(infra.transfers.nextId(), sourceAccountId, null, targetIban,
                AMOUNT, BASE.plusSeconds(minutesFromBase * 60L));
    }

    private void record(String caseName, Transfer t) {
        infra.transfers.add(t);
        seeded.put(caseName, t.id());
    }
}
