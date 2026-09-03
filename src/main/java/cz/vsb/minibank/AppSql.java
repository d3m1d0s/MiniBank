package cz.vsb.minibank;

import cz.vsb.minibank.application.audit.AppLogger;
import cz.vsb.minibank.application.auth.AuthService;
import cz.vsb.minibank.application.config.BootstrapServices;
import cz.vsb.minibank.application.audit.FraudAlertAuditLogObserver;
import cz.vsb.minibank.application.config.MinibankProperties;
import cz.vsb.minibank.application.auth.PasswordEncoder;
import cz.vsb.minibank.application.payment.PaymentDispatcher;
import cz.vsb.minibank.application.auth.Pbkdf2PasswordEncoder;
import cz.vsb.minibank.application.audit.TransferAuditLogObserver;
import cz.vsb.minibank.demo.DemoScenario;
import cz.vsb.minibank.domain.customer.User;
import cz.vsb.minibank.domain.customer.UserRole;
import cz.vsb.minibank.domain.repository.UserRepository;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import cz.vsb.minibank.ui.console.ConsoleMenu;

/**
 * Console entry point for MiniBank using PostgreSQL-backed persistence.
 */
public class AppSql {

    public static void main(String[] args) {
        AppLogger.info("app", "Starting MiniBank in SQL mode");

        Bootstrap infra = new Bootstrap(
                MinibankProperties.sqlUrl(),
                MinibankProperties.sqlUser(),
                MinibankProperties.sqlPassword());

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

        PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        AuthService authService = new AuthService(infra.users, encoder);

        // The dataset and the logins together, because that is one decision: the logins exist to
        // reach the dataset, and gating them apart would invent a state nobody asked for.
        if (MinibankProperties.demoEnabled()) {
            int customerId = new DemoScenario(
                    infra.customers,
                    infra.accounts,
                    infra.transfers,
                    infra.alerts,
                    infra.uowFactory,
                    app.feePolicy
            ).seed();
            ensureDemoUsers(infra, encoder, customerId);
        } else {
            announceDemoDataIsOff();
        }

        // After the seed, when there is one - it commits a settled payment out of this bank and
        // therefore writes a dispatch this process owes - and before the menu, so nothing a
        // customer does queues up behind whatever an earlier run left unsent. Unconditional: the
        // rows it looks for are whatever the database already holds, and a run without the demo
        // owes them just as much as a run with it.
        dispatcher.sweepPending();

        ConsoleMenu menu = new ConsoleMenu(app, infra, authService);
        menu.run();

        AppLogger.info("app", "MiniBank terminated");
    }

    /**
     * Tells the operator, once, that this run created nothing and what that leaves them.
     *
     * Without the demo the console still requires a login, and a database that nobody else has
     * put a user in has none to give: the prompt below cannot be answered. That is the intended
     * end state - the switch exists so a real deployment brings its own data - but reaching it
     * in silence would look like a broken build rather than a setting, and the login loop
     * re-asks forever, so the way out is named here too.
     *
     * Printed to stdout rather than through AppLogger because it is guidance for the person at
     * the prompt three lines below, not an event for the audit file, and AppLogger writes to
     * stderr, which need not be where that person is looking. The key names carry the message:
     * neither the credentials the demo would have created nor the connection string, which can
     * itself carry a password, belongs on a sign-in screen.
     *
     * "is not true" rather than "=false", because false is not the only value that reaches here:
     * anything that is not true switches the demo off, an empty value and a typo included.
     * Quoting a false back at somebody who typed something else would send them looking for a
     * line they never wrote.
     */
    private static void announceDemoDataIsOff() {
        System.out.println();
        System.out.println("=== Demo data is off ===");
        System.out.println(MinibankProperties.DEMO_ENABLED + " is not true, so this run created"
                + " no sample customer and no demo logins");
        System.out.println("in the database " + MinibankProperties.SQL_URL + " points at.");
        System.out.println("The login below accepts only an account that database already holds,"
                + " and there is");
        System.out.println("no way past it without one. End the input to quit, or start again"
                + " without the flag");
        System.out.println("to have the demo data and the demo logins created.");
    }

    /**
     * Ensures that demo users for customer login and fraud analyst login exist.
     * Runs in its own unit of work because the demo scenario has already committed.
     */
    private static void ensureDemoUsers(Bootstrap infra, PasswordEncoder encoder, int customerId) {
        UserRepository users = infra.users;

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            if (users.findByUsername("alice").isEmpty()) {
                int uid = users.nextId();
                byte[] salt = encoder.generateSalt();
                byte[] hash = encoder.hash("alice123".toCharArray(), salt);
                users.save(new User(uid, "alice", hash, salt, UserRole.CUSTOMER, customerId));
            }

            if (users.findByUsername("fraud").isEmpty()) {
                int uid = users.nextId();
                byte[] salt = encoder.generateSalt();
                byte[] hash = encoder.hash("fraud123".toCharArray(), salt);
                users.save(new User(uid, "fraud", hash, salt, UserRole.FRAUD_ANALYST, null));
            }

            scope.uow().commit();
        }
    }
}
