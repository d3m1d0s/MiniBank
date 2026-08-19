package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AlertDetailDto;
import cz.vsb.minibank.api.dto.HistoryItemDto;
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

    /**
     * The components each record carried before this fact reached the wire.
     *
     * Named rather than counted, so that a second field added beside the boundary is reported as
     * an ambiguity instead of being silently read as the boundary itself.
     */
    private static final Set<String> HISTORY_ITEM_FIELDS = Set.of(
            "id", "createdAt", "amount", "status", "fromIban", "toIban", "declineReason");

    private static final Set<String> TRANSFER_INFO_FIELDS = Set.of(
            "id", "code", "status", "fromIban", "fromBalance", "toIban", "amount", "feeAmount",
            "createdAt", "authMethod");

    private static final Set<String> TRANSFER_DETAILS_FIELDS = Set.of(
            "id", "fromIban", "fromBalance", "toIban", "amount", "feeAmount", "status",
            "createdAt", "settledAt", "message", "declineReason", "authMethod", "triesLeft",
            "authValidUntil");

    @TempDir
    Path tempDir;

    private Bootstrap infra;
    private FraudController fraudController;
    private AuthorizationController authorizationController;

    private int payerCustomerId;
    private int alertOnHeldToOutside;
    private int alertOnSentInside;

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
                    "boundary@example.com", new Address("Hlavni 1", "Ostrava"));
            infra.customers.save(payer);

            int payerAccountId = infra.accounts.nextId();
            Account payerAccount = new Account(payerAccountId, PAYER_IBAN,
                    Money.czk(5_000_000), Money.czk(4_000_000));
            infra.accounts.save(payerAccount);
            payer.addAccountId(payerAccountId);
            infra.customers.save(payer);

            int neighbourCustomerId = infra.customers.nextId();
            Customer neighbour = new Customer(neighbourCustomerId, "Neighbour",
                    "neighbour@example.com", new Address("Hlavni 2", "Ostrava"));
            infra.customers.save(neighbour);

            int inBankAccountId = infra.accounts.nextId();
            Account inBankAccount = new Account(inBankAccountId, IN_BANK_IBAN,
                    Money.czk(1_000), Money.czk(4_000_000));
            infra.accounts.save(inBankAccount);
            neighbour.addAccountId(inBankAccountId);
            infra.customers.save(neighbour);

            // SENT, credited here: send resolves a destination, so nothing is owed to the
            // network and nothing is written about a dispatch.
            Transfer sentInside = created(payerAccountId, IN_BANK_IBAN.value(), 0);
            sentInside.send(payerAccount, inBankAccount, feePolicy, BASE);
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

            scope.uow().commit();
        }

        // The account that turns up afterwards. Nothing about the payment that already left for
        // its number changes, and that is what the first test asserts.
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            int lateCustomerId = infra.customers.nextId();
            Customer late = new Customer(lateCustomerId, "Late Arrival",
                    "late@example.com", new Address("Hlavni 3", "Ostrava"));
            infra.customers.save(late);

            int lateAccountId = infra.accounts.nextId();
            infra.accounts.save(new Account(lateAccountId, LATE_ARRIVAL_IBAN,
                    Money.czk(1_000), Money.czk(4_000_000)));
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
