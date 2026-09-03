package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import cz.vsb.minibank.domain.fraud.FraudAlert;
import cz.vsb.minibank.domain.fraud.FraudAlertState;
import cz.vsb.minibank.domain.fraud.RiskDecision;

class FraudAlertMetadataTest {

    @Test
    void constructorWithMetadataSetsAllFields() {
        var tags = List.of("NEW_BENEFICIARY", "ABOVE_LIMIT");

        var alert = new FraudAlert(
                1,
                42,
                "Suspicious amount",
                72,
                "alice",
                tags
        );

        assertEquals(1, alert.id());
        assertEquals(42, alert.transferId());
        assertEquals(FraudAlertState.NEW, alert.state());
        assertEquals("Suspicious amount", alert.reason());
        assertNotNull(alert.createdAt());

        assertEquals(72, alert.riskScore());
        assertEquals("alice", alert.assignee());
        assertEquals(tags, alert.tags());
        assertNull(alert.decisionComment(),
                "an alert nobody has decided carries no analyst comment");
    }

    @Test
    void hydratingAnAlertWithNoCreationInstantIsRefused() {
        // The same rule Transfer states, and stated on both or the two aggregates disagree about
        // what a stored row must carry. The constructor has already stamped Instant.now(), so
        // overwriting only a non-null value gave the alert the instant it was read at - which
        // moves the createdFrom and createdTo filters the queue is searched with, differently on
        // every read. On the SQL side, where a NULL column arrives as a null Instant with nothing
        // to parse, this guard is the only thing in the way.
        var alert = new FraudAlert(11, 42, "Above the alert threshold");

        var thrown = assertThrows(DataIntegrityException.class,
                () -> alert.hydrateForLoad(FraudAlertState.OK, "Above the alert threshold", null));
        assertTrue(thrown.getMessage().contains("11"));
    }

    @Test
    void hydrateForLoadRestoresMetadataFromPersistence() {
        var alert = new FraudAlert(10, 1000, "ignored");

        var createdAt = Instant.parse("2025-01-01T10:15:30Z");
        var tags = List.of("NEW_BENEFICIARY");

        alert.hydrateForLoad(
                FraudAlertState.SUSPICIOUS,
                "Above daily limit",
                createdAt,
                85,
                "bob",
                tags
        );

        assertEquals(FraudAlertState.SUSPICIOUS, alert.state());
        assertEquals("Above daily limit", alert.reason());
        assertEquals(createdAt, alert.createdAt());
        assertEquals(85, alert.riskScore());
        assertEquals("bob", alert.assignee());
        assertEquals(tags, alert.tags());
    }

    @Test
    void riskDecisionProducesConsistentRiskScoreOrdering() {
        var d1 = new RiskDecision(false, false, null);            // no risk
        var d2 = new RiskDecision(true, false, "auth only");      // medium
        var d3 = new RiskDecision(false, true, "alert only");     // high
        var d4 = new RiskDecision(true, true, "auth + alert");    // very high

        assertTrue(d1.riskScore() < d2.riskScore());
        assertTrue(d2.riskScore() <= d4.riskScore());
        assertTrue(d3.riskScore() <= d4.riskScore());
    }
}
