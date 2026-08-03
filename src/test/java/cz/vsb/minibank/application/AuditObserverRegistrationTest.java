package cz.vsb.minibank.application;

import cz.vsb.minibank.api.MinibankApiConfig;
import cz.vsb.minibank.domain.FraudAlertEvents;
import cz.vsb.minibank.domain.TransferEvents;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins where the audit observers are attached.
 *
 * The event buses are static and live for the whole process, so an observer attached from a
 * place that runs more than once accumulates. That is what used to happen: BootstrapServices
 * attached them in its constructor, and because the suite builds one per test class, a single
 * status change reached the audit log hundreds of times in one run.
 */
public class AuditObserverRegistrationTest {

    @TempDir
    Path tempDir;

    private Bootstrap infra;

    @BeforeEach
    void clearBuses() {
        // Both directions matter: the assertions below count from a known zero, and whatever
        // ran before this class must not contribute to the count.
        TransferEvents.clearObservers();
        FraudAlertEvents.clearObservers();
        infra = new Bootstrap(tempDir.resolve("data.json").toString());
    }

    @AfterEach
    void clearBusesAgain() {
        TransferEvents.clearObservers();
        FraudAlertEvents.clearObservers();
    }

    @Test
    void buildingTheServicesAttachesNothing() {
        new BootstrapServices(infra.customers, infra.accounts, infra.transfers,
                infra.alerts, infra.uowFactory);
        new BootstrapServices(infra.customers, infra.accounts, infra.transfers,
                infra.alerts, infra.uowFactory);

        assertEquals(0, TransferEvents.observerCount(),
                "BootstrapServices is not a composition root and must attach no observer");
        assertEquals(0, FraudAlertEvents.observerCount(),
                "BootstrapServices is not a composition root and must attach no observer");
    }

    @Test
    void theApiCompositionRootAttachesEachObserverOnce() {
        new MinibankApiConfig().bootstrapServices(infra);

        assertEquals(1, TransferEvents.observerCount(),
                "The API's composition root must attach the transfer audit observer exactly once");
        assertEquals(1, FraudAlertEvents.observerCount(),
                "The API's composition root must attach the fraud audit observer exactly once");
    }
}
