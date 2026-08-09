package cz.vsb.minibank;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.FraudAlertAuditLogObserver;
import cz.vsb.minibank.application.MinibankProperties;
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

        int customerId = new DemoScenario(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory,
                app.feePolicy
        ).seed();

        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);
        menu.run();
    }
}
