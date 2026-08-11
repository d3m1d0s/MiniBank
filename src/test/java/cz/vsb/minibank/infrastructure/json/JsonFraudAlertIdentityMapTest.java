package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertObserver;
import cz.vsb.minibank.domain.FraudAlertState;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The JSON fraud alert lookup by transfer, seen from inside a transaction that has already loaded
 * the alert it is about to find again.
 *
 * byId and all() on this repository probe the identity map before they build anything, and so does
 * the SQL repository's lookup by transfer. This one did not: it scanned the store, built a fresh
 * FraudAlert from the stored row and put that in the map, evicting whatever the transaction was
 * holding. Every fraud use case goes through it, so the instance it evicted was routinely one an
 * analyst had just decided.
 *
 * That costs two things. The reading, because the map is what every later byId answers from, and
 * the announcement, because both units of work drain domain events by walking the identity map -
 * an instance dropped out of it takes its unpublished FraudAlertStateChanged with it while the
 * save it registered still writes the new state, leaving the store and the audit trail disagreeing
 * about the same alert.
 */
class JsonFraudAlertIdentityMapTest {

    @TempDir
    Path tempDir;

    private static final int ALERT_ID = 9001;
    private static final int TRANSFER_ID = 5001;
    private static final Instant DECIDED_AT = Instant.parse("2025-01-02T09:00:00Z");

    @Test
    void byTransferIdReturnsTheAlertThisTransactionHasAlreadyChanged() throws IOException {
        Bootstrap infra = new Bootstrap(storeWithOneOpenAlert().toString());

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            FraudAlert loaded = infra.alerts.byId(ALERT_ID).orElseThrow();
            loaded.approve("analyst", DECIDED_AT);
            infra.alerts.save(loaded);

            FraudAlert byTransfer = infra.alerts.byTransferId(TRANSFER_ID).orElseThrow();

            assertSame(loaded, byTransfer,
                    "one transaction must hold one instance of an alert, however it was asked for");
            assertEquals(FraudAlertState.OK, byTransfer.state(),
                    "and the verdict it already recorded must still be on it");
            assertEquals("analyst", byTransfer.decidedBy());
            assertEquals(DECIDED_AT, byTransfer.resolvedAt());

            assertSame(loaded, infra.alerts.byId(ALERT_ID).orElseThrow(),
                    "and the lookup must not have left a stale copy behind for byId to find");

            scope.uow().commit();
        }
    }

    /**
     * The same displacement seen from the audit trail. The decision reaches the store either way,
     * because save() registered its mutation before the alert was evicted; what disappears is the
     * event, so the analyst's verdict is written and never announced.
     */
    @Test
    void aVerdictSurvivingALookupByTransferIsStillAnnouncedOnCommit() throws IOException {
        Bootstrap infra = new Bootstrap(storeWithOneOpenAlert().toString());

        List<FraudAlertState> announced = new ArrayList<>();
        FraudAlertObserver recorder = (alert, oldState, newState) -> announced.add(newState);
        infra.events.register(recorder);

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            FraudAlert loaded = infra.alerts.byId(ALERT_ID).orElseThrow();
            loaded.markSuspicious("Confirmed by the cardholder", "analyst", DECIDED_AT);
            infra.alerts.save(loaded);

            infra.alerts.byTransferId(TRANSFER_ID).orElseThrow();

            scope.uow().commit();
        }

        assertEquals(List.of(FraudAlertState.SUSPICIOUS), announced,
                "the state change must be published, not swallowed with the instance that recorded it");

        FraudAlert persisted = new Bootstrap(storePath().toString()).alerts.byId(ALERT_ID).orElseThrow();
        assertEquals(FraudAlertState.SUSPICIOUS, persisted.state(),
                "and the store must say the same thing the announcement did");
    }

    private Path storeWithOneOpenAlert() throws IOException {
        Path store = storePath();
        Files.writeString(store, """
                {
                  "customers": [], "accounts": [], "transfers": [],
                  "fraudAlerts": [
                    { "id": %d, "transferId": %d, "state": "NEW",
                      "reason": "New beneficiary and an amount above the limit",
                      "createdAt": "2025-01-01T10:15:30Z" }
                  ]
                }
                """.formatted(ALERT_ID, TRANSFER_ID));
        return store;
    }

    private Path storePath() {
        return tempDir.resolve("data.json");
    }
}
