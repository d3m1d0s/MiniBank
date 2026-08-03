package cz.vsb.minibank;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.FraudAlertAuditLogObserver;
import cz.vsb.minibank.application.MinibankProperties;
import cz.vsb.minibank.application.TransferAuditLogObserver;
import cz.vsb.minibank.demo.DemoScenario;
import cz.vsb.minibank.domain.FraudAlertEvents;
import cz.vsb.minibank.domain.TransferEvents;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.ui.console.ConsoleMenu;

/**
 * Console entry point for MiniBank using JSON-based persistence.
 */
public class App {

    public static void main(String[] args) {
        // The event buses are static, so this belongs to whatever starts the process exactly
        // once, not to BootstrapServices, which anything may construct any number of times.
        TransferEvents.register(new TransferAuditLogObserver());
        FraudAlertEvents.register(new FraudAlertAuditLogObserver());

        Bootstrap infra = new Bootstrap(MinibankProperties.jsonPath());
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
