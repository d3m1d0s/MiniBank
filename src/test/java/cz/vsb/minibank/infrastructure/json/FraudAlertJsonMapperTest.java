package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertState;
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

        var alert = new FraudAlert(1, 42, "Suspicious", 72, "alice", tags, "Note");
        alert.hydrateForLoad(
                FraudAlertState.NEW,
                "Suspicious",
                createdAt,
                72,
                "alice",
                tags,
                "Note"
        );

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
        assertEquals(alert.notes(), restored.notes());
    }
}
