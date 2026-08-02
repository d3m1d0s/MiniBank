package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.CardPayment;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertState;
import cz.vsb.minibank.domain.RuleBasedRiskService;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.ZeroFeePolicy;
import cz.vsb.minibank.domain.exceptions.ConflictException;
import cz.vsb.minibank.domain.exceptions.InvalidStateTransitionException;
import cz.vsb.minibank.domain.exceptions.TransferUnderReviewException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A8: an open fraud alert blocks the customer's confirmation, and an analyst's APPROVE clears
 * the transfer for that confirmation instead of sending it.
 *
 * What this replaced created the alert and never read one back. A 12 000 payment raised alert
 * 203 in state NEW, the customer typed the fixed code, the money left, and the alert was still
 * sitting in the queue as NEW - so every case here is written to fail against that arrangement
 * and not merely to describe this one. The gate is asserted through the application service
 * rather than through a controller, because the console and the demo runner call the service
 * directly and a check a controller can forget is not a gate.
 *
 * Two backlog items close underneath it and have their own sections: approving a suspicious
 * transaction, which was a use case with no reachable code, and recording a negative verdict on
 * a payment that has already gone, which used to answer 409 and roll the analyst's work back
 * with it.
 */
class FraudReviewGateTest {

    private static final int CUSTOMER_ID = 1;
    private static final int OTHER_CUSTOMER_ID = 2;
    private static final int ACCOUNT_ID = 100;
    private static final int OTHER_ACCOUNT_ID = 101;

    private static final String ACCOUNT_IBAN = "CZ6508000000192000145399";
    private static final String OTHER_IBAN = "CZ4308000000192000145407";
    /** Held by no account of this bank and not a saved beneficiary, so payments to it are untrusted. */
    private static final String EXTERNAL_IBAN = "CZ2001000000000012345678";

    private static final Money OPENING = Money.czk(200_000);

    /** Under the 5 000 untrusted threshold, so it settles at creation with no alert. */
    private static final double SETTLES_NOW = 1_000;
    /** Over 5 000 and under 10 000: authorization, no alert. The unflagged control case. */
    private static final double NEEDS_AUTH_ONLY = 6_000;
    /** Over the 10 000 alert threshold. The backlog item's own example. */
    private static final double RAISES_ALERT = 13_000;

    @TempDir
    Path tempDir;

    private AccountRepository accounts;
    private TransferRepository transfers;
    private FraudAlertRepository alerts;

    private TransferApplicationService service;
    private FraudApplicationService fraudService;

    @BeforeEach
    void setUp() {
        Bootstrap infra = new Bootstrap(tempDir.resolve("data.json").toString());
        accounts = infra.accounts;
        transfers = infra.transfers;
        alerts = infra.alerts;

        Customer customer = new Customer(CUSTOMER_ID, "Payer", "payer@example.com",
                new Address("Hlavni 1", "Ostrava"));
        customer.addAccountId(ACCOUNT_ID);
        infra.customers.save(customer);
        accounts.save(new Account(ACCOUNT_ID, new IBAN(ACCOUNT_IBAN), OPENING, Money.czk(500_000)));

        Customer other = new Customer(OTHER_CUSTOMER_ID, "Payee", "payee@example.com",
                new Address("Hlavni 2", "Ostrava"));
        other.addAccountId(OTHER_ACCOUNT_ID);
        infra.customers.save(other);
        accounts.save(new Account(OTHER_ACCOUNT_ID, new IBAN(OTHER_IBAN),
                Money.czk(5_000), Money.czk(500_000)));

        BootstrapServices services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts,
                new ZeroFeePolicy(), new RuleBasedRiskService(), new FixedOtpValidator(),
                new FakePaymentNetworkGateway(), infra.uowFactory);
        service = services.transferService;
        fraudService = services.fraudService;
    }

    // ------------------------------------------------------------------ the gate

    /**
     * The whole of A8 in one case. A payment that raises an alert is held rather than left
     * waiting for a code, and the code - a code the validator really does accept - is refused
     * while the alert is open.
     *
     * The refusal has to be free: no OTP attempt spent on something that is not about the code,
     * no money moved, and the transfer left exactly as it was found so that it is still
     * releasable and still cancellable.
     */
    @Test
    void aFlaggedPaymentCannotBeAuthorizedWhileItsAlertIsOpen() {
        int id = payExternal(RAISES_ALERT);

        assertEquals(TransferStatus.HELD_FOR_REVIEW, status(id));
        assertEquals(FraudAlertState.NEW, alertFor(id).state());
        assertNull(transfer(id).authValidUntil(),
                "a held transfer has no authorization window, so the five minutes cannot run "
                        + "out while the alert waits in the queue");

        assertThrows(TransferUnderReviewException.class,
                () -> service.authorizePayment(CUSTOMER_ID, id, FixedOtpValidator.DEMO_OTP),
                "a valid code must not move money past an open alert");

        assertEquals(TransferStatus.HELD_FOR_REVIEW, status(id), "the refusal must change nothing");
        assertEquals(0, transfer(id).authAttempts(),
                "a refusal that is not about the code must spend no OTP attempt");
        assertNull(transfer(id).declineReason());
        assertEquals(OPENING, balance(), "nothing may have moved");
        assertEquals(FraudAlertState.NEW, alertFor(id).state(), "the alert is still the queue's");
    }

    /**
     * The refusal is a 409 and it is its own 409. A caller told only that the transfer is "not
     * waiting for authorization" - which is what the generic conflict says - has no way to see
     * that the bank is looking at their payment, which is the precedent A5 and A9 set.
     */
    @Test
    void theRefusalIsAConflictWithItsOwnType() {
        int id = payExternal(RAISES_ALERT);

        ConflictException e = assertThrows(ConflictException.class,
                () -> service.authorizePayment(CUSTOMER_ID, id, FixedOtpValidator.DEMO_OTP));
        assertTrue(e instanceof TransferUnderReviewException,
                "it must carry its own type, or the customer gets the generic wording");
    }

    /**
     * Three wrong codes decline a waiting transfer. A held one must not even reach the counter,
     * or a customer could burn their own payment by guessing at a code that could never have
     * worked - and the analyst's queue would then hold an alert on a transfer nobody stopped.
     */
    @Test
    void repeatedAttemptsOnAHeldTransferNeverExhaustIt() {
        int id = payExternal(RAISES_ALERT);

        for (int i = 0; i < 5; i++) {
            assertThrows(TransferUnderReviewException.class,
                    () -> service.authorizePayment(CUSTOMER_ID, id, "999999"));
        }

        assertEquals(TransferStatus.HELD_FOR_REVIEW, status(id));
        assertEquals(0, transfer(id).authAttempts());
    }

    /**
     * The ordering rule on ConflictException, on the new code as much as on the old one. A 409
     * that a stranger can reach proves the transfer id is real, and transfer ids are small
     * consecutive integers.
     */
    @Test
    void aStrangerGetsNotFoundAndNotTheReviewConflict() {
        int id = payExternal(RAISES_ALERT);

        assertThrows(cz.vsb.minibank.domain.exceptions.NotFoundException.class,
                () -> service.authorizePayment(OTHER_CUSTOMER_ID, id, FixedOtpValidator.DEMO_OTP),
                "the ownership 404 has to win before any conflict is raised");
    }

    // ------------------------------------------------------------------ APPROVE

    /**
     * B3. "Approve the suspicious transaction" is a use case that exists now.
     *
     * The branch this replaced fired only on a CREATED transfer, and no alerted transfer was
     * ever CREATED, so the analyst's approval did nothing to the transfer at all. Approval now
     * releases it - and releases it to the customer, not to the network: the money moves on the
     * customer's own confirmation and not a moment earlier.
     */
    @Test
    void anApprovedPaymentIsReleasedToTheCustomerAndOnlyThenSent() {
        int id = payExternal(RAISES_ALERT);

        fraudService.approve(id);

        assertEquals(FraudAlertState.OK, alertFor(id).state());
        assertEquals(TransferStatus.WAITING_AUTH, status(id),
                "approval clears the transfer for confirmation, it does not send it");
        assertEquals(OPENING, balance(), "approving an alert must debit nothing");

        service.authorizePayment(CUSTOMER_ID, id, FixedOtpValidator.DEMO_OTP);

        assertEquals(TransferStatus.SENT, status(id));
        assertEquals(OPENING.minus(Money.czk(RAISES_ALERT)), balance());
    }

    /**
     * The released transfer waits for its owner rather than for a stopwatch.
     *
     * An analyst approves when they reach the queue, which is not when the customer is at their
     * screen. Starting a five-minute window on somebody else's click would expire most released
     * payments before their owner ever saw them, and the customer's only recourse would be to
     * resubmit the same payment and have it held again.
     */
    @Test
    void releasingAHeldTransferStartsNoDeadlineTheCustomerCannotSee() {
        int id = payExternal(RAISES_ALERT);
        fraudService.approve(id);

        assertNull(transfer(id).authValidUntil(),
                "a released transfer must not carry a window the customer did not start");
        assertEquals(0, transfer(id).authAttempts());
        assertEquals(TransferStatus.WAITING_AUTH, status(id));
    }

    /**
     * A second analyst working a stale queue is refused, and refused before anything is written.
     * Without this, an APPROVE arriving after a DECLINE would put a confirmed-fraud alert back
     * to OK.
     */
    @Test
    void aSecondApprovalIsRefusedAndChangesNothing() {
        int id = payExternal(RAISES_ALERT);
        fraudService.approve(id);

        assertThrows(InvalidStateTransitionException.class, () -> fraudService.approve(id));

        assertEquals(FraudAlertState.OK, alertFor(id).state());
        assertEquals(TransferStatus.WAITING_AUTH, status(id),
                "the transfer must not be released twice");
    }

    // ------------------------------------------------------------------ DECLINE

    /**
     * A declined transfer stays declined, whatever code its owner has. The customer's
     * authorization path answers the generic conflict here rather than the review one: the
     * alert is resolved and the payment is over.
     */
    @Test
    void aDeclinedPaymentCanNeverBeAuthorized() {
        int id = payExternal(RAISES_ALERT);

        fraudService.decline(id, "confirmed mule account");

        assertEquals(FraudAlertState.SUSPICIOUS, alertFor(id).state());
        assertEquals(TransferStatus.DECLINED, status(id));

        ConflictException e = assertThrows(ConflictException.class,
                () -> service.authorizePayment(CUSTOMER_ID, id, FixedOtpValidator.DEMO_OTP));
        assertFalse(e instanceof TransferUnderReviewException,
                "a declined transfer is not under review; it is simply over");

        assertEquals(TransferStatus.DECLINED, status(id));
        assertEquals(OPENING, balance(), "a declined payment must never move money");
    }

    /**
     * And it cannot be resurrected by an approval that was already in flight when it was
     * declined. SUSPICIOUS is terminal, so the whitewash has no route back.
     */
    @Test
    void anApprovalArrivingAfterADeclineIsRefused() {
        int id = payExternal(RAISES_ALERT);
        fraudService.decline(id, "confirmed mule account");

        assertThrows(InvalidStateTransitionException.class, () -> fraudService.approve(id));

        assertEquals(FraudAlertState.SUSPICIOUS, alertFor(id).state());
        assertEquals(TransferStatus.DECLINED, status(id));
    }

    /**
     * B2. Confirmed fraud on money that has already left is recordable.
     *
     * The realistic sequence, in order: the alert is cleared, the customer confirms, the money
     * goes, and the fraud is confirmed afterwards. Declining then used to raise a 409 from
     * Transfer.decline that rolled the whole unit of work back - the verdict, and with it the
     * assignee, tags and notes the analyst had typed - so APPROVE was the only decision a
     * settled transfer would accept and confirmed fraud was filed as OK.
     */
    @Test
    void anAnalystCanRecordANegativeVerdictOnAPaymentThatAlreadyWent() {
        int id = payExternal(RAISES_ALERT);
        fraudService.approve(id);
        service.authorizePayment(CUSTOMER_ID, id, FixedOtpValidator.DEMO_OTP);

        assertEquals(TransferStatus.SENT, status(id));
        assertEquals(FraudAlertState.OK, alertFor(id).state());

        fraudService.decline(id, "victim confirmed the payment was not theirs");

        assertEquals(FraudAlertState.SUSPICIOUS, alertFor(id).state(),
                "the verdict must survive on a transfer that has already been sent");
        assertEquals(TransferStatus.SENT, status(id),
                "and it must not pretend to have reversed it - there is no reversal path here");
        assertEquals(OPENING.minus(Money.czk(RAISES_ALERT)), balance(),
                "recording fraud does not put the money back");
    }

    /**
     * The verdict is added to the case file, not written over it. Overwriting {@code reason}
     * left a confirmed-fraud alert whose only stated reason was that somebody had declined it,
     * with no record of what had been suspicious about the payment in the first place.
     */
    @Test
    void decliningKeepsTheReasonTheRulesRaisedTheAlertFor() {
        int id = payExternal(RAISES_ALERT);
        String raisedFor = alertFor(id).reason();
        assertNotNull(raisedFor);

        fraudService.decline(id, "victim confirmed the payment was not theirs");

        String after = alertFor(id).reason();
        assertTrue(after.contains(raisedFor),
                "the risk reason must survive the verdict: " + after);
        assertTrue(after.contains("victim confirmed the payment was not theirs"),
                "and so must the analyst's: " + after);
    }

    // ------------------------------------------------------------------ the customer's way out

    /**
     * A held transfer has no expiry of any kind and nothing ages an unreviewed alert out of the
     * queue, so the owner's own cancel is the only thing standing between them and an
     * indefinite wait. It must never be gated on the review.
     */
    @Test
    void theCustomerCanStillCancelAHeldPayment() {
        int id = payExternal(RAISES_ALERT);
        assertEquals(TransferStatus.HELD_FOR_REVIEW, status(id));

        service.cancelPayment(CUSTOMER_ID, id);

        assertEquals(TransferStatus.DECLINED, status(id));
        assertEquals("Canceled by customer", transfer(id).declineReason());
        assertEquals(OPENING, balance());
    }

    /**
     * And an approval landing on a payment the customer withdrew while it was held resolves the
     * alert without resurrecting anything. The analyst's decision is still worth recording;
     * there is simply nothing left to release.
     */
    @Test
    void approvingAnAlertOnACancelledPaymentResurrectsNothing() {
        int id = payExternal(RAISES_ALERT);
        service.cancelPayment(CUSTOMER_ID, id);

        fraudService.approve(id);

        assertEquals(FraudAlertState.OK, alertFor(id).state());
        assertEquals(TransferStatus.DECLINED, status(id), "an approval must not undo a cancel");
        assertEquals(OPENING, balance());
    }

    /**
     * A declining analyst must not rewrite the customer's own record of why their payment
     * stopped. The alert takes the verdict; the transfer keeps the reason it already had.
     */
    @Test
    void decliningAnAlertOnACancelledPaymentKeepsTheCustomersOwnReason() {
        int id = payExternal(RAISES_ALERT);
        service.cancelPayment(CUSTOMER_ID, id);

        fraudService.decline(id, "Declined by fraud analyst");

        assertEquals(FraudAlertState.SUSPICIOUS, alertFor(id).state());
        assertEquals("Canceled by customer", transfer(id).declineReason(),
                "the customer cancelled this payment; the record must still say so");
    }

    // ------------------------------------------------------------------ nothing else changed

    /**
     * The control case, and the one that would catch an over-broad gate. A payment that needs
     * authorization but raises no alert takes exactly the path it always did: waiting, with a
     * five-minute window its owner started, and settled by its owner's code.
     */
    @Test
    void anUnflaggedPaymentIsUnaffected() {
        int id = payExternal(NEEDS_AUTH_ONLY);

        assertEquals(TransferStatus.WAITING_AUTH, status(id));
        assertTrue(alerts.byTransferId(id).isEmpty(), "no alert may be raised under the threshold");
        assertNotNull(transfer(id).authValidUntil(),
                "an ordinary authorization still runs on the customer's own five minutes");

        service.authorizePayment(CUSTOMER_ID, id, FixedOtpValidator.DEMO_OTP);

        assertEquals(TransferStatus.SENT, status(id));
        assertEquals(OPENING.minus(Money.czk(NEEDS_AUTH_ONLY)), balance());
    }

    /** And a payment below every threshold still settles at creation, with no alert and no OTP. */
    @Test
    void aSmallPaymentStillSettlesAtCreation() {
        int id = payExternal(SETTLES_NOW);

        assertEquals(TransferStatus.SENT, status(id));
        assertTrue(alerts.byTransferId(id).isEmpty());
        assertEquals(OPENING.minus(Money.czk(SETTLES_NOW)), balance());
    }

    // ------------------------------------------------------------------ the state machines

    /**
     * The transfer's own guards, asserted on the domain object rather than through a service,
     * because they are the backstop under the gate: if the gate were ever bypassed, these are
     * what would still refuse to move the money.
     */
    @Test
    void theTransferRefusesEveryTransitionThatIsNotOnTheMachine() {
        // HELD_FOR_REVIEW -> SENT has no path: send() admits CREATED and WAITING_AUTH only.
        Transfer held = heldTransfer();
        assertThrows(InvalidStateTransitionException.class,
                () -> held.send(sourceAccount(), null, new ZeroFeePolicy()),
                "a held transfer must not be settleable by any caller");

        // HELD_FOR_REVIEW -> HELD_FOR_REVIEW: a transfer is held once, at creation.
        assertThrows(InvalidStateTransitionException.class,
                () -> held.holdForReview(new CardPayment(held.amount(), "****0000")));

        // HELD_FOR_REVIEW -> WAITING_AUTH is the one edge out, and only through release.
        assertThrows(InvalidStateTransitionException.class,
                () -> held.requestAuthorization(new CardPayment(held.amount(), "****0000")),
                "requestAuthorization is CREATED-only and must not double as a release");

        // No OTP counter runs on a held transfer.
        assertThrows(InvalidStateTransitionException.class,
                () -> held.registerFailedOtpAttempt(TransferApplicationService.MAX_OTP_ATTEMPTS));

        held.releaseForAuthorization();
        assertEquals(TransferStatus.WAITING_AUTH, held.status());

        // WAITING_AUTH -> HELD_FOR_REVIEW has no path either: an alert is raised at creation.
        assertThrows(InvalidStateTransitionException.class,
                () -> held.holdForReview(new CardPayment(held.amount(), "****0000")));

        // And a transfer can only be released once.
        assertThrows(InvalidStateTransitionException.class, held::releaseForAuthorization);

        // Nothing leaves SENT.
        Transfer sent = heldTransfer();
        sent.releaseForAuthorization();
        sent.send(sourceAccount(), null, new ZeroFeePolicy());
        assertEquals(TransferStatus.SENT, sent.status());
        assertThrows(InvalidStateTransitionException.class, () -> sent.decline("too late"));
        assertThrows(InvalidStateTransitionException.class, sent::releaseForAuthorization);

        // A held transfer that was declined stays out of reach of the analyst's release.
        Transfer cancelled = heldTransfer();
        cancelled.decline("Canceled by customer");
        assertThrows(InvalidStateTransitionException.class, cancelled::releaseForAuthorization,
                "an approval must not resurrect a payment its owner withdrew");
    }

    /**
     * The alert's guards. NEW is the only state anything can be cleared from and SUSPICIOUS is
     * terminal, which is what stops a stale APPROVE undoing a confirmed-fraud verdict on money
     * that has already gone - the one case where the transfer has no guard left to lean on.
     */
    @Test
    void theAlertRefusesEveryTransitionThatIsNotOnTheMachine() {
        FraudAlert a = new FraudAlert(1, 1, "New beneficiary + high amount");
        assertEquals(FraudAlertState.NEW, a.state());

        a.approve();
        assertEquals(FraudAlertState.OK, a.state());
        assertThrows(InvalidStateTransitionException.class, a::approve, "OK -> OK is refused");

        // OK -> SUSPICIOUS is allowed on purpose: it is how fraud confirmed after the money
        // left gets on the record.
        a.markSuspicious("confirmed afterwards");
        assertEquals(FraudAlertState.SUSPICIOUS, a.state());

        assertThrows(InvalidStateTransitionException.class, a::approve,
                "SUSPICIOUS -> OK would whitewash a confirmed-fraud alert");
        assertThrows(InvalidStateTransitionException.class,
                () -> a.markSuspicious("again"),
                "SUSPICIOUS is terminal");

        FraudAlert b = new FraudAlert(2, 2, "New beneficiary + high amount");
        b.markSuspicious("declined outright");
        assertThrows(InvalidStateTransitionException.class, b::approve);
    }

    /**
     * The invariant the whole change establishes, asserted directly: an alert in state NEW never
     * sits on a transfer its owner can confirm. Every other alert state is a decision that has
     * been taken.
     */
    @Test
    void anOpenAlertNeverSitsOnAConfirmableTransfer() {
        int held = payExternal(RAISES_ALERT);
        int released = payExternal(RAISES_ALERT);
        int declined = payExternal(RAISES_ALERT);
        int cancelled = payExternal(RAISES_ALERT);

        fraudService.approve(released);
        fraudService.decline(declined, "no");
        service.cancelPayment(CUSTOMER_ID, cancelled);

        for (FraudAlert alert : alerts.all()) {
            TransferStatus s = transfer(alert.transferId()).status();
            if (alert.state() == FraudAlertState.NEW) {
                assertTrue(s == TransferStatus.HELD_FOR_REVIEW || s == TransferStatus.DECLINED,
                        "alert " + alert.id() + " is open but its transfer is " + s);
            }
        }

        assertEquals(TransferStatus.HELD_FOR_REVIEW, status(held));
        assertEquals(TransferStatus.WAITING_AUTH, status(released));
        assertEquals(TransferStatus.DECLINED, status(declined));
        assertEquals(TransferStatus.DECLINED, status(cancelled));
    }

    // ------------------------------------------------------------------ fixture

    private int payExternal(double amountCzk) {
        return service.submitPaymentToIban(CUSTOMER_ID, ACCOUNT_ID, EXTERNAL_IBAN, amountCzk, "");
    }

    private Transfer transfer(int id) {
        return transfers.byId(id).orElseThrow();
    }

    private TransferStatus status(int id) {
        return transfer(id).status();
    }

    private FraudAlert alertFor(int transferId) {
        return alerts.byTransferId(transferId).orElseThrow();
    }

    private Money balance() {
        return accounts.byId(ACCOUNT_ID).orElseThrow().balance();
    }

    private Account sourceAccount() {
        return accounts.byId(ACCOUNT_ID).orElseThrow();
    }

    /** A detached transfer in HELD_FOR_REVIEW, for the domain-only transition assertions. */
    private Transfer heldTransfer() {
        Transfer t = new Transfer(9_000, ACCOUNT_ID, null, EXTERNAL_IBAN,
                Money.czk(RAISES_ALERT), "CZK");
        t.holdForReview(new CardPayment(t.amount(), "****0000"));
        return t;
    }
}
