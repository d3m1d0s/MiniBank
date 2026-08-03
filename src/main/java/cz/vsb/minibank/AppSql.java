package cz.vsb.minibank;

import cz.vsb.minibank.application.AppLogger;
import cz.vsb.minibank.application.AuthService;
import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.FraudAlertAuditLogObserver;
import cz.vsb.minibank.application.MinibankProperties;
import cz.vsb.minibank.application.PasswordEncoder;
import cz.vsb.minibank.application.Pbkdf2PasswordEncoder;
import cz.vsb.minibank.application.TransferAuditLogObserver;
import cz.vsb.minibank.demo.DemoScenario;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.repository.UserRepository;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
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

        PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        AuthService authService = new AuthService(infra.users, encoder);

        int customerId = new DemoScenario(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory,
                app.feePolicy
        ).seed();
        ensureDemoUsers(infra, encoder, customerId);

        ConsoleMenu menu = new ConsoleMenu(app, infra, authService);
        menu.run();

        AppLogger.info("app", "MiniBank terminated");
    }

    /**
     * Ensures that demo users for customer login and fraud analyst login exist.
     * Runs in its own unit of work because the demo scenario has already committed.
     */
    private static void ensureDemoUsers(Bootstrap infra, PasswordEncoder encoder, int customerId) {
        UserRepository users = infra.users;

        UnitOfWork uow = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
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

            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }
}
