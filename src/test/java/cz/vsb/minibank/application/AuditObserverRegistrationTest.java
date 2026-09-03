package cz.vsb.minibank.application;

import cz.vsb.minibank.api.config.MinibankApiConfig;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import cz.vsb.minibank.application.auth.RefusingOtpValidator;
import cz.vsb.minibank.application.config.BootstrapServices;

/**
 * Pins where the observers are attached.
 *
 * The bus is no longer static, so an observer registered twice no longer outlives the thing that
 * registered it - but it still writes every audit line twice for as long as it is there, and the
 * defect this pins is unchanged: BootstrapServices used to attach a pair in its constructor, and
 * the suite builds one per test class.
 *
 * The stake is higher for the third observer than for the two audit ones. A duplicated
 * {@code PaymentDispatcher} would not double a log line, it would offer every settled payment to
 * the network twice; that the gateway is idempotent is what makes it survivable, not what makes it
 * correct.
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
        new MinibankApiConfig().bootstrapServices(infra, new RefusingOtpValidator());

        assertEquals(2, infra.events.observerCount(),
                "the services bean must attach the transfer observer and the fraud observer, one"
                        + " each, and nothing else");
    }

    /**
     * The dispatcher has a bean of its own rather than a third line in the services bean, because
     * the startup sweep needs the same instance. That makes the bean method the single attachment
     * point, which is what this pins.
     */
    @Test
    void theApiCompositionRootAttachesThePaymentDispatcherOnce() {
        MinibankApiConfig config = new MinibankApiConfig();
        BootstrapServices services = config.bootstrapServices(infra, new RefusingOtpValidator());

        config.paymentDispatcher(infra, services);

        assertEquals(3, infra.events.observerCount(),
                "the two audit observers and the dispatcher, one each");
    }
}
