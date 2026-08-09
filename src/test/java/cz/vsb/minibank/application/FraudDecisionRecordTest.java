package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertState;
import cz.vsb.minibank.domain.RuleBasedRiskService;
import cz.vsb.minibank.domain.ZeroFeePolicy;
import cz.vsb.minibank.domain.exceptions.ValidationException;
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
 * B1's remaining half: an analyst's verdict, their name and the moment they gave it are recorded
 * and can be read back - plus the one thing a tag may not contain.
 *
 * fraud_alerts.decision and resolved_at have been declared in db/init/schema.sql since the table
 * was created, written by nothing and read by nothing. decided_by did not exist at all, and no
 * analyst identity could reach the service: FraudController checked the role and threw the user
 * away. So an approved alert stored "OK" and no record of who approved it or when.
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
                alertId, "APPROVE", null, null, null, null, "anna.analyst");

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
                alertId, "DECLINE", "card reported stolen", null, null, null, "bob.analyst");

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
     * A comma in a tag is refused at the single validation point.
     *
     * The two backends store tags differently and neither can represent one: the SQL repository
     * joins them with commas and splits on commas coming back, so "high,risk" is written as one
     * tag and read as two, while the JSON store keeps a real list and returns the one tag that
     * was typed. The same alert would then answer the same question differently depending on
     * which store it came from.
     */
    @Test
    void aCommaInsideATagIsRefused() {
        int transferId = flaggedPayment();
        int alertId = alertFor(transferId).id();

        ValidationException refused = assertThrows(ValidationException.class,
                () -> services.fraudService.decideAndUpdateAlert(
                        alertId, "APPROVE", null, null, List.of("high,risk"), null, "anna.analyst"));
        assertTrue(refused.getMessage().contains("high,risk"),
                "the message names the offending tag, for the log: " + refused.getMessage());
    }

    /**
     * And the refusal costs the analyst nothing else.
     *
     * The check runs before the switch, so a bad tag cannot roll back a verdict that has already
     * been applied to the aggregate. Raised from inside the switch it would have taken the
     * approval down with it and the analyst would have had to decide the alert twice.
     */
    @Test
    void aRefusedTagLeavesTheAlertExactlyAsItWas() {
        int transferId = flaggedPayment();
        int alertId = alertFor(transferId).id();

        assertThrows(ValidationException.class, () -> services.fraudService.decideAndUpdateAlert(
                alertId, "APPROVE", null, "someone", List.of("ok", "bad,tag"), "notes", "anna.analyst"));

        FraudAlert untouched = alertFor(transferId);
        assertEquals(FraudAlertState.NEW, untouched.state(), "the verdict must not have been applied");
        assertNull(untouched.decision());
        assertNull(untouched.decidedBy());
        assertNull(untouched.assignee(), "nor any of the metadata that travelled with it");
        assertTrue(untouched.tags().isEmpty());
        assertEquals(cz.vsb.minibank.domain.TransferStatus.HELD_FOR_REVIEW,
                infra.transfers.byId(transferId).orElseThrow().status(),
                "and the transfer stays held, so the alert can still be decided");

        // A tag without a comma goes through, so the rule refuses one character and not tags.
        services.fraudService.decideAndUpdateAlert(
                alertId, "APPROVE", null, "someone", List.of("ok", "good tag"), "notes", "anna.analyst");
        assertEquals(List.of("ok", "good tag"), alertFor(transferId).tags());
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
