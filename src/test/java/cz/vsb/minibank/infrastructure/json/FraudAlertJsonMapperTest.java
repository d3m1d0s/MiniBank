package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.domain.fraud.FraudAlert;
import cz.vsb.minibank.domain.fraud.FraudAlertState;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.infrastructure.json.dto.JsonFraudAlert;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FraudAlertJsonMapperTest {

    @Test
    void toDtoAndBackPreservesNewMetadataFields() {
        var tags = List.of("NEW_BENEFICIARY", "ABOVE_LIMIT");
        var createdAt = Instant.parse("2025-01-01T10:15:30Z");

        var alert = new FraudAlert(1, 42, "Suspicious", 72, "alice", tags);
        alert.hydrateForLoad(
                FraudAlertState.NEW,
                "Suspicious",
                createdAt,
                72,
                "alice",
                tags
        );
        alert.hydrateDecision("APPROVE", "alice", createdAt, "beneficiary confirmed by phone");

        JsonFraudAlert dto = JsonMapper.toDto(alert);
        FraudAlert restored = JsonMapper.toDomain(dto);

        assertEquals(alert.id(), restored.id());
        assertEquals(alert.transferId(), restored.transferId());
        assertEquals(alert.state(), restored.state());
        assertEquals(alert.reason(), restored.reason());
        assertEquals(alert.createdAt(), restored.createdAt());
        assertEquals(alert.riskScore(), restored.riskScore());
        assertEquals(alert.assignee(), restored.assignee());
        assertEquals(alert.tags(), restored.tags());
        assertEquals(alert.decision(), restored.decision());
        assertEquals(alert.decidedBy(), restored.decidedBy());
        assertEquals(alert.resolvedAt(), restored.resolvedAt());
        assertEquals(alert.decisionComment(), restored.decisionComment(),
                "the analyst's comment is a field of its own now, and a round trip that dropped"
                        + " it would put the desk back to reading it out of the risk reason");
    }

    /**
     * A state nobody can read is refused, and the reason it matters is what it used to do
     * instead.
     *
     * The parse and the hydrate call sat inside one swallowing catch, so an unreadable state left
     * the alert on the constructor's default - NEW. A decided alert therefore came back open, and
     * on this project's fraud gate an alert that is open again blocks the transfer it is attached
     * to. It also discarded every other field in the same call: the reason, the risk score, the
     * assignee, the tags and the notes.
     */
    @Test
    void aStoredAlertWhoseStateCannotBeReadIsRefusedRatherThanReopened() {
        JsonFraudAlert dto = row();
        dto.state = "DEFINITELY_NOT_A_STATE";

        DataIntegrityException thrown =
                assertThrows(DataIntegrityException.class, () -> JsonMapper.toDomain(dto));
        assertTrue(thrown.getMessage().contains("7"),
                "the refusal must name the row, so a corrupt store can be found");
        assertTrue(thrown.getMessage().contains("DEFINITELY_NOT_A_STATE"),
                "and name the value, so it can be corrected");
    }

    /**
     * Its creation instant, on the same terms. Absent used to mean the alert quietly acquired the
     * moment it was read, which moves the createdFrom and createdTo filters the queue is searched
     * with - and moves them again on the next read.
     */
    @Test
    void aStoredAlertWithNoCreationInstantIsRefusedRatherThanDatedOnLoad() {
        JsonFraudAlert dto = row();
        dto.createdAt = null;

        assertThrows(DataIntegrityException.class, () -> JsonMapper.toDomain(dto));
    }

    @Test
    void aStoredAlertWhoseCreationInstantCannotBeReadIsRefused() {
        JsonFraudAlert dto = row();
        dto.createdAt = "last Tuesday";

        DataIntegrityException thrown =
                assertThrows(DataIntegrityException.class, () -> JsonMapper.toDomain(dto));
        assertTrue(thrown.getMessage().contains("last Tuesday"));
    }

    /**
     * The instant the alert was resolved, on the same terms - but this one is legitimately absent
     * on an open alert, and that is what made swallowing it worse rather than milder. An
     * unreadable string used to land on exactly the value a legal row has, so a decided alert came
     * back carrying a decision and a decidedBy with nothing saying when, and nothing anywhere
     * complained.
     */
    @Test
    void aStoredAlertWhoseResolutionInstantCannotBeReadIsRefused() {
        JsonFraudAlert dto = row();
        dto.decision = "APPROVE";
        dto.decidedBy = "alice";
        dto.resolvedAt = "last Tuesday";

        DataIntegrityException thrown =
                assertThrows(DataIntegrityException.class, () -> JsonMapper.toDomain(dto));
        assertTrue(thrown.getMessage().contains("7"),
                "the refusal must name the row, so a corrupt store can be found");
        assertTrue(thrown.getMessage().contains("last Tuesday"),
                "and name the value, so it can be corrected");
    }

    /**
     * Guards against over-tightening: no resolution instant is what every open alert carries, and
     * what every alert written before the field existed carries.
     */
    @Test
    void aStoredAlertWithNoResolutionInstantStillLoads() {
        JsonFraudAlert dto = row();
        dto.resolvedAt = null;

        FraudAlert restored = JsonMapper.toDomain(dto);

        assertNull(restored.resolvedAt());
        assertEquals(Instant.parse("2025-01-01T10:15:30Z"), restored.createdAt());
    }

    private static JsonFraudAlert row() {
        JsonFraudAlert dto = new JsonFraudAlert();
        dto.id = 7;
        dto.transferId = 42;
        dto.state = "OK";
        dto.reason = "Above the alert threshold";
        dto.createdAt = "2025-01-01T10:15:30Z";
        return dto;
    }
}
