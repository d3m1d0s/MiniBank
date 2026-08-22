package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertNote;
import cz.vsb.minibank.domain.FraudAlertState;
import cz.vsb.minibank.domain.RuleBasedRiskService;
import cz.vsb.minibank.domain.ZeroFeePolicy;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rest of the alert lifecycle: an analyst's verdict, their name and the moment they gave it are recorded
 * and can be read back - plus who the alert is waiting on.
 *
 * fraud_alerts.decision and resolved_at have been declared in db/init/schema.sql since the table
 * was created, written by nothing and read by nothing. decided_by did not exist at all, and no
 * analyst identity could reach the service: FraudController checked the role and threw the user
 * away. So an approved alert stored "OK" and no record of who approved it or when.
 *
 * assignee was the same shape of defect one column over, and the assignment cases below are what
 * closed it: it was stored, printed on both desks and filtered on, and the only way to write it
 * was to send back the value that had just been read, which meant it could never hold anything
 * but null and the filter could never match a row.
 */
class FraudDecisionRecordTest {

    private static final int CUSTOMER_ID = 1;
    private static final int ACCOUNT_ID = 100;

    private static final String ACCOUNT_IBAN = "CZ6508000000192000145399";
    /** Not a saved beneficiary, so payments to it are untrusted and can trip the alert rule. */
    private static final String EXTERNAL_IBAN = "CZ2001000000000012345678";

    /** Above the 10 000 fraud-alert threshold, so it is held for review and raises an alert. */
    private static final double FLAGGED = 12_000;

    private static final Instant WHEN = Instant.parse("2026-03-04T10:15:30Z");

    @TempDir
    Path tempDir;

    private Bootstrap infra;
    private BootstrapServices services;

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());

        Customer customer = new Customer(CUSTOMER_ID, "Fraud Probe", "fraud@example.com",
                new Address("Hlavni 1", "Ostrava"));
        customer.addAccountId(ACCOUNT_ID);
        infra.customers.save(customer);
        infra.accounts.save(new Account(ACCOUNT_ID, new IBAN(ACCOUNT_IBAN),
                Money.czk(500_000), Money.czk(400_000)));

        services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts,
                new ZeroFeePolicy(),
                new RuleBasedRiskService(),
                new FixedOtpValidator(),
                new FakePaymentNetworkGateway(),
                infra.uowFactory,
                Clock.fixed(WHEN, TransferApplicationService.BANK_ZONE));
    }

    /**
     * An APPROVE over the HTTP path, which is the only path that carries an analyst.
     *
     * All three assertions are on an alert read back out of the store, not on the instance the
     * service mutated: a field written on the aggregate and dropped at the store boundary is
     * exactly the defect being corrected, and only a round trip can see the difference.
     */
    @Test
    void anApprovalRecordsTheAnalystAndTheMomentTheyDecided() {
        int transferId = flaggedPayment();
        int alertId = alertFor(transferId).id();

        assertNull(alertFor(transferId).decision(), "an open alert carries no verdict");
        assertNull(alertFor(transferId).resolvedAt());

        services.fraudService.decideAndUpdateAlert(
                alertId, "APPROVE", null, null, "anna.analyst");

        FraudAlert stored = alertFor(transferId);
        assertEquals(FraudAlertState.OK, stored.state());
        assertEquals(FraudAlert.DECISION_APPROVE, stored.decision(),
                "the verdict must be on the record, not only inferable from the state");
        assertEquals("anna.analyst", stored.decidedBy(),
                "and the analyst who gave it, which nothing could record before this change");
        assertEquals(WHEN, stored.resolvedAt(),
                "stamped from the injected clock, like every other instant this project records");
    }

    /** The same for a DECLINE, which writes the other verdict. */
    @Test
    void aDeclineRecordsTheOtherVerdictAgainstTheSameAnalyst() {
        int transferId = flaggedPayment();
        int alertId = alertFor(transferId).id();

        services.fraudService.decideAndUpdateAlert(
                alertId, "DECLINE", "card reported stolen", null, "bob.analyst");

        FraudAlert stored = alertFor(transferId);
        assertEquals(FraudAlertState.SUSPICIOUS, stored.state());
        assertEquals(FraudAlert.DECISION_DECLINE, stored.decision());
        assertEquals("bob.analyst", stored.decidedBy());
        assertEquals(WHEN, stored.resolvedAt());
        assertEquals("card reported stolen", stored.decisionComment(),
                "the analyst's own words, in the field that holds only those");
        assertEquals("New beneficiary + high amount", stored.reason(),
                "and the sentence the rules wrote, untouched beside it");
    }

    /**
     * The comment an analyst types is kept whichever of the three verdicts they press, and it no
     * longer lands in the sentence the rules wrote.
     *
     * TWO DEFECTS, ONE CASE. Only DECLINE ever kept the comment at all: FraudAlert exposed no
     * writer of it other than markSuspicious, so on APPROVE and on ANNOTATE the text was read off
     * the request, validated and dropped. And where it was kept, it was appended into
     * {@code reason}, which is the only record of why the rules raised the alert, so one line
     * carried two facts with two different authors and nothing could tell a reader which half was
     * which.
     *
     * Three alerts rather than three presses on one, because a verdict is refused a second time by
     * design and this is about what one press keeps. Every assertion reads the alert back out of
     * the store: a field written on the aggregate and dropped at the store boundary is exactly the
     * shape of defect being closed, and only a round trip can see the difference.
     */
    @Test
    void theAnalystsCommentIsKeptOnEveryVerdictAndTheRiskReasonIsLeftAlone() {
        record Verdict(String token, String comment) { }

        List<Verdict> verdicts = List.of(
                new Verdict("APPROVE", "beneficiary confirmed by phone"),
                new Verdict("DECLINE", "card reported stolen"),
                new Verdict("ANNOTATE", "waiting on the card scheme"));

        for (Verdict verdict : verdicts) {
            int transferId = flaggedPayment();
            int alertId = alertFor(transferId).id();
            String raised = alertFor(transferId).reason();

            services.fraudService.decideAndUpdateAlert(
                    alertId, verdict.token(), verdict.comment(), null, "anna.analyst");

            FraudAlert stored = alertFor(transferId);

            assertEquals(verdict.comment(), stored.decisionComment(),
                    verdict.token() + " dropped the analyst's comment");
            assertEquals(raised, stored.reason(),
                    verdict.token() + " wrote into the reason the rules raised the alert for; the"
                            + " comment has a field of its own and must stay in it");
        }
    }

    /**
     * A decision taken without a comment records none, and clears none.
     *
     * Absent is what the two desks send when the box is empty, and it is the common case on an
     * APPROVE: most cleared alerts are cleared without a word. Treating that as an instruction to
     * erase would make every wordless verdict destroy what the previous one recorded.
     */
    @Test
    void aVerdictWithNoCommentRecordsNoneAndLeavesTheRiskReasonExactlyAsItWas() {
        int transferId = flaggedPayment();
        int alertId = alertFor(transferId).id();
        String raised = alertFor(transferId).reason();

        services.fraudService.decideAndUpdateAlert(alertId, "APPROVE", "   ", null, "anna.analyst");

        assertEquals(raised, alertFor(transferId).reason());
        assertNull(alertFor(transferId).decisionComment(),
                "a blank box records nothing rather than a blank comment");
    }

    /**
     * A refusal with no comment is not taken at all, and nothing is written on either side of it.
     *
     * DECLINE is the one verdict that stops somebody's money for good, and the sentence it carries
     * is what the payer is shown. A blank box used to become "Declined by fraud analyst" on the
     * payer's copy, which read there exactly like a reason somebody had given and was the bank
     * answering a question the analyst had left unanswered. Both desks keep their Decline control
     * dead while the box is empty; this is the same rule on a direct call, where no desk is holding
     * it.
     *
     * The guard stands before anything is written, so the two assertions after the throw are the
     * point of the case: a refused decision leaves the alert open and the payment held, rather than
     * half deciding it and then failing.
     */
    @Test
    void aRefusalWithNoReasonIsRefusedAndTheBankWritesNoSentenceOfItsOwn() {
        for (String noReason : new String[] { null, "   " }) {
            int transferId = flaggedPayment();
            int alertId = alertFor(transferId).id();

            cz.vsb.minibank.domain.exceptions.ValidationException refused = assertThrows(
                    cz.vsb.minibank.domain.exceptions.ValidationException.class,
                    () -> services.fraudService.decideAndUpdateAlert(
                            alertId, "DECLINE", noReason, null, "bob.analyst"),
                    "a refusal with nothing in the comment box must be turned away");
            assertEquals("A declined alert needs a reason: say why this payment is refused",
                    refused.getMessage());

            FraudAlert stored = alertFor(transferId);
            assertEquals(FraudAlertState.NEW, stored.state(), "the alert is still waiting");
            assertNull(stored.decision());
            assertNull(stored.decisionComment());

            var payment = infra.transfers.byId(transferId).orElseThrow();
            assertEquals(cz.vsb.minibank.domain.TransferStatus.HELD_FOR_REVIEW, payment.status(),
                    "the payment stays where it was rather than being half refused");
            assertNull(payment.declineReason(),
                    "and the payer is told nothing, least of all a sentence the bank wrote for the"
                            + " analyst");
        }
    }

    /**
     * ANNOTATE is the one route that reaches an alert somebody has already decided.
     *
     * APPROVE and DECLINE refuse a second verdict and would take the writing down with them, so
     * this is where a follow-up has to land. A second comment REPLACES the first, and that is the
     * deliberate half: the comment belongs to the decision of record, and the journal beside it is
     * where an analyst puts something that has to survive. Both are pinned here, so that a later
     * change cannot quietly make the comment append again and leave the two fields saying the same
     * thing in two ways.
     */
    @Test
    void anAnnotationReachesADecidedAlertAndItsCommentReplacesTheEarlierOne() {
        int transferId = flaggedPayment();
        int alertId = alertFor(transferId).id();

        services.fraudService.decideAndUpdateAlert(
                alertId, "APPROVE", "beneficiary confirmed by phone", null, "anna.analyst");
        services.fraudService.decideAndUpdateAlert(
                alertId, "ANNOTATE", "customer called back to confirm", null, "bob.analyst");

        FraudAlert stored = alertFor(transferId);

        assertEquals("customer called back to confirm", stored.decisionComment(),
                "the comment on an alert is the one that came with the last decision taken");
        assertEquals(FraudAlertState.OK, stored.state(), "an annotation is not a verdict");
        assertEquals("anna.analyst", stored.decidedBy(),
                "and it does not put the annotator's name on somebody else's decision");
    }

    /**
     * Two notes on one alert: both kept, in the order they were written, each under its own author
     * and moment.
     *
     * This is the whole of what the journal replaced. The alert used to carry one notes string that
     * every save overwrote, so the second analyst to write anything destroyed what the first had
     * written with nothing telling either of them, and the column recorded neither who had written
     * what nor when.
     *
     * The clock is fixed for this fixture, so the two entries share an instant. That is the awkward
     * case rather than an accident of the fixture: what keeps them in order is the tie break behind
     * the ordering, and a journal that lost it would swap two entries between two reads of one
     * screen.
     */
    @Test
    void twoNotesOnOneAlertAreBothKeptInTheOrderTheyWereWritten() {
        int transferId = flaggedPayment();
        int alertId = alertFor(transferId).id();

        services.fraudService.decideAndUpdateAlert(
                alertId, "ANNOTATE", null, "called the payer, no answer", "anna.analyst");
        services.fraudService.decideAndUpdateAlert(
                alertId, "ANNOTATE", null, "payer called back, confirms the payment", "bob.analyst");

        List<FraudAlertNote> journal = infra.alerts.notesOf(alertId);

        assertEquals(2, journal.size(), "appending must never replace");
        assertEquals("called the payer, no answer", journal.get(0).text());
        assertEquals("anna.analyst", journal.get(0).author());
        assertEquals(WHEN, journal.get(0).writtenAt());
        assertEquals("payer called back, confirms the payment", journal.get(1).text());
        assertEquals("bob.analyst", journal.get(1).author(),
                "each entry names the analyst who wrote it, which one shared column could not");
        assertEquals(alertId, journal.get(1).alertId());
    }

    /** A decision with both a comment and a note writes both, into the two places they belong. */
    @Test
    void aDecisionCarryingACommentAndANoteWritesBoth() {
        int transferId = flaggedPayment();
        int alertId = alertFor(transferId).id();

        services.fraudService.decideAndUpdateAlert(
                alertId, "APPROVE", "beneficiary confirmed by phone",
                "spoke to the payer on the number we hold", "anna.analyst");

        assertEquals("beneficiary confirmed by phone", alertFor(transferId).decisionComment());

        List<FraudAlertNote> journal = infra.alerts.notesOf(alertId);
        assertEquals(1, journal.size());
        assertEquals("spoke to the payer on the number we hold", journal.get(0).text());
    }

    /**
     * A blank note is not an entry, and an alert nobody has written on has an empty journal rather
     * than none at all.
     *
     * Both desks send the box on every decision and it is empty on most of them. A journal that
     * grew a wordless entry per press would be longer without saying more, and a screen reading it
     * would have to decide what to print for a note with no text.
     */
    @Test
    void aBlankNoteIsNotAnEntry() {
        int transferId = flaggedPayment();
        int alertId = alertFor(transferId).id();

        assertTrue(infra.alerts.notesOf(alertId).isEmpty(),
                "an alert arrives with an empty journal");

        services.fraudService.decideAndUpdateAlert(alertId, "ANNOTATE", null, "   ", "anna.analyst");

        assertTrue(infra.alerts.notesOf(alertId).isEmpty());
    }

    /**
     * The console has no login, so its decisions name nobody rather than inventing somebody.
     *
     * Null is the honest answer and a placeholder like "console" would be a name in an audit
     * record that names no person. The verdict and the timestamp are still recorded, so the row
     * is not empty - only the analyst is unknown.
     */
    @Test
    void aDecisionFromTheConsoleRecordsAVerdictButNoAnalyst() {
        int transferId = flaggedPayment();

        services.fraudService.approve(transferId);

        FraudAlert stored = alertFor(transferId);
        assertEquals(FraudAlert.DECISION_APPROVE, stored.decision());
        assertNull(stored.decidedBy(), "a surface with no login names nobody");
        assertEquals(WHEN, stored.resolvedAt(), "but the moment is still on the record");
    }

    /**
     * An alert can be taken into an analyst's name and given back again.
     *
     * Both halves are the case, and the second is the one the old shape could not express at all.
     * Assignment used to travel on the decision, where a blank assignee meant "leave it alone",
     * so there was no value a caller could send that released an alert. A queue whose rows can be
     * claimed and never returned is worse than one with no assignment in it.
     */
    @Test
    void anAlertIsTakenIntoAnAnalystsNameAndGivenBack() {
        int transferId = flaggedPayment();
        int alertId = alertFor(transferId).id();

        assertNull(alertFor(transferId).assignee(), "an alert arrives in nobody's name");

        services.fraudService.assign(alertId, "anna.analyst");
        assertEquals("anna.analyst", alertFor(transferId).assignee());

        services.fraudService.assign(alertId, null);
        assertNull(alertFor(transferId).assignee(), "and can be put back on the queue");
    }

    /** Blank is the same instruction as absent: it releases the alert rather than holding it. */
    @Test
    void aBlankAssigneeReleasesTheAlertAndAPaddedOneIsTrimmed() {
        int transferId = flaggedPayment();
        int alertId = alertFor(transferId).id();

        services.fraudService.assign(alertId, "  anna.analyst  ");
        assertEquals("anna.analyst", alertFor(transferId).assignee(),
                "the assignee filter is a containment test, so a stored name with spaces around"
                        + " it would match on some queries and not on others");

        services.fraudService.assign(alertId, "   ");
        assertNull(alertFor(transferId).assignee());
    }

    /**
     * Taking an alert is not a verdict, and deciding one is not an assignment.
     *
     * The two facts have different lifetimes: an analyst takes an alert when they start looking
     * at it and decides it when they have finished, and an alert that has been decided can still
     * be handed to somebody to follow up. That is why assignment left the decision route rather
     * than staying on it as one more field.
     */
    @Test
    void assignmentAndTheVerdictDoNotDisturbEachOther() {
        int transferId = flaggedPayment();
        int alertId = alertFor(transferId).id();

        services.fraudService.assign(alertId, "anna.analyst");

        FraudAlert claimed = alertFor(transferId);
        assertEquals(FraudAlertState.NEW, claimed.state(), "claiming an alert decides nothing");
        assertNull(claimed.decision());
        assertEquals(cz.vsb.minibank.domain.TransferStatus.HELD_FOR_REVIEW,
                infra.transfers.byId(transferId).orElseThrow().status(),
                "and it leaves the payment where it was");

        services.fraudService.decideAndUpdateAlert(
                alertId, "APPROVE", null, "looked fine", "bob.analyst");

        FraudAlert decided = alertFor(transferId);
        assertEquals("anna.analyst", decided.assignee(),
                "the decision must not clear the assignee, and must not overwrite it with the"
                        + " name of whoever happened to decide");
        assertEquals("bob.analyst", decided.decidedBy());

        // And an already-decided alert can still be handed on, which is the follow-up case.
        services.fraudService.assign(alertId, "carol.analyst");
        assertEquals("carol.analyst", alertFor(transferId).assignee());
        assertEquals(FraudAlert.DECISION_APPROVE, alertFor(transferId).decision(),
                "without reopening anything");
    }

    /**
     * The tags column is read only now: a decision leaves whatever is stored in it alone.
     *
     * Tags were a field on the wire, a column and a validation rule with nothing that could
     * produce one, and the two desks disagreed about what a decision did to them - one sent an
     * empty list, which cleared the column, and the other sent nothing, which did not. With the
     * field gone from the request and from the service, neither can happen. The alert detail
     * still reads the column, so anything already stored in it survives a decision and is still
     * shown, which is what this pins.
     */
    @Test
    void aDecisionLeavesTheStoredTagsAlone() {
        int transferId = flaggedPayment();
        int alertId = alertFor(transferId).id();

        try (cz.vsb.minibank.infrastructure.uow.UowScope scope =
                     new cz.vsb.minibank.infrastructure.uow.UowScope(infra.uowFactory.begin())) {
            FraudAlert alert = infra.alerts.byId(alertId).orElseThrow();
            alert.replaceTags(List.of("manual-review"));
            infra.alerts.save(alert);
            scope.uow().commit();
        }

        services.fraudService.decideAndUpdateAlert(
                alertId, "APPROVE", "looked fine", null, "anna.analyst");

        assertEquals(List.of("manual-review"), alertFor(transferId).tags());
    }

    /** An alert nobody ever raised cannot be assigned, and says so rather than doing nothing. */
    @Test
    void assigningAnAlertThatDoesNotExistIsRefused() {
        assertThrows(cz.vsb.minibank.domain.exceptions.NotFoundException.class,
                () -> services.fraudService.assign(4242, "anna.analyst"));
    }

    // ------------------------------------------------------------------ fixture

    private int flaggedPayment() {
        return services.transferService.submitPaymentToIban(
                CUSTOMER_ID, ACCOUNT_ID, EXTERNAL_IBAN, FLAGGED, "over the alert threshold").transferId();
    }

    /** Read back through the repository, so every assertion is about what the store holds. */
    private FraudAlert alertFor(int transferId) {
        FraudAlert alert = infra.alerts.byTransferId(transferId).orElseThrow();
        assertNotNull(alert);
        return alert;
    }
}
