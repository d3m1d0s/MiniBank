package cz.vsb.minibank;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.demo.DemoScenario;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.ui.console.ConsoleMenu;

/**
 * Console entry point for MiniBank using JSON-based persistence.
 */
public class App {

    /** Overridable so the store can be pointed anywhere; storage/ is gitignored. */
    static final String DEFAULT_DATA_PATH = "storage/data.json";

    public static void main(String[] args) {
        String dataPath = System.getProperty("minibank.json.path", DEFAULT_DATA_PATH);
        Bootstrap infra = new Bootstrap(dataPath);
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
