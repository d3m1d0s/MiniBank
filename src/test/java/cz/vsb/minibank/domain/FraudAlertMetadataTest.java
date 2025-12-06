package cz.vsb.minibank.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
                tags,
                "Initial note"
        );

        assertEquals(1, alert.id());
        assertEquals(42, alert.transferId());
        assertEquals(FraudAlertState.NEW, alert.state());
        assertEquals("Suspicious amount", alert.reason());
        assertNotNull(alert.createdAt());

        assertEquals(72, alert.riskScore());
        assertEquals("alice", alert.assignee());
        assertEquals(tags, alert.tags());
        assertEquals("Initial note", alert.notes());
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
                tags,
                "Manual review"
        );

        assertEquals(FraudAlertState.SUSPICIOUS, alert.state());
        assertEquals("Above daily limit", alert.reason());
        assertEquals(createdAt, alert.createdAt());
        assertEquals(85, alert.riskScore());
        assertEquals("bob", alert.assignee());
        assertEquals(tags, alert.tags());
        assertEquals("Manual review", alert.notes());
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
