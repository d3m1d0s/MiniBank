package cz.vsb.minibank;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.FraudAlertAuditLogObserver;
import cz.vsb.minibank.application.MinibankProperties;
import cz.vsb.minibank.application.PaymentDispatcher;
import cz.vsb.minibank.application.TransferAuditLogObserver;
import cz.vsb.minibank.demo.DemoScenario;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.ui.console.ConsoleMenu;

/**
 * Console entry point for MiniBank using JSON-based persistence.
 */
public class App {

    public static void main(String[] args) {
        Bootstrap infra = new Bootstrap(MinibankProperties.jsonPath());

        // The bus belongs to this Bootstrap and lives exactly as long as it does. Registering
        // here, at the one place that starts the process, and not in BootstrapServices, which
        // anything may construct any number of times.
        infra.events.register(new TransferAuditLogObserver());
        infra.events.register(new FraudAlertAuditLogObserver());

        BootstrapServices app = new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory
        );

        // The third observer, and the only one that does something rather than record something.
        // Attached here for the reason the audit pair is, and from the gateway BootstrapServices
        // built, so this process has exactly one thing talking to the network.
        PaymentDispatcher dispatcher = new PaymentDispatcher(
                app.paymentGateway, infra.transfers, infra.uowFactory);
        infra.events.register(dispatcher);

        int customerId = new DemoScenario(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory,
                app.feePolicy
        ).seed();

        // After the seed, which commits a settled payment out of this bank and therefore writes a
        // dispatch this process owes, and before the menu, so nothing a customer does queues up
        // behind whatever an earlier run left unsent.
        dispatcher.sweepPending();

        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);
        menu.run();
    }
}
