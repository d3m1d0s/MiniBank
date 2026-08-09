package cz.vsb.minibank.application;

import cz.vsb.minibank.api.MinibankApiConfig;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins where the audit observers are attached.
 *
 * The bus is no longer static, so an observer registered twice no longer outlives the thing that
 * registered it - but it still writes every audit line twice for as long as it is there, and the
 * defect this pins is unchanged: BootstrapServices used to attach a pair in its constructor, and
 * the suite builds one per test class.
 */
public class AuditObserverRegistrationTest {

    @TempDir
    Path tempDir;

    private Bootstrap infra;

    @BeforeEach
    void freshInfrastructure() {
        // A new Bootstrap is a new bus, which is what makes these assertions absolute rather
        // than relative to whatever ran before. The static buses this replaced could not be
        // asserted about this way without clearing them first.
        infra = new Bootstrap(tempDir.resolve("data.json").toString());
    }

    @Test
    void aFreshBootstrapAttachesNoObserverOfItsOwn() {
        assertEquals(0, infra.events.observerCount(),
                "the infrastructure must attach nothing; registering is the composition root's job");
    }

    @Test
    void buildingTheServicesAttachesNothing() {
        new BootstrapServices(infra.customers, infra.accounts, infra.transfers,
                infra.alerts, infra.uowFactory);
        new BootstrapServices(infra.customers, infra.accounts, infra.transfers,
                infra.alerts, infra.uowFactory);

        assertEquals(0, infra.events.observerCount(),
                "BootstrapServices is not a composition root and must attach no observer");
    }

    @Test
    void theApiCompositionRootAttachesEachObserverOnce() {
        new MinibankApiConfig().bootstrapServices(infra);

        assertEquals(2, infra.events.observerCount(),
                "the API's composition root must attach the transfer observer and the fraud"
                        + " observer, one each");
    }
}
