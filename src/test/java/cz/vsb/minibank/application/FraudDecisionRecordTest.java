package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FraudAlert;
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
        assertTrue(stored.reason().contains("card reported stolen"),
                "the analyst's reason is still appended to the rules' own");
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
                alertId, "APPROVE", null, "looked fine", "anna.analyst");

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
