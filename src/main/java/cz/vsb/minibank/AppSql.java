package cz.vsb.minibank;

import cz.vsb.minibank.application.AppLogger;
import cz.vsb.minibank.application.AuthService;
import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.PasswordEncoder;
import cz.vsb.minibank.application.Pbkdf2PasswordEncoder;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.UserRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
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

        String jdbcUrl = System.getProperty("minibank.jdbcUrl", "jdbc:postgresql://localhost:5432/minibank");
        String dbUser = System.getProperty("minibank.dbUser", "minibank");
        String dbPass = System.getProperty("minibank.dbPass", "minibank");

        Bootstrap infra = new Bootstrap(jdbcUrl, dbUser, dbPass);
        BootstrapServices app = new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory
        );

        PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        UserRepository users = infra.users;
        AuthService authService = new AuthService(users, encoder);

        ensureDemoDataSql(infra, encoder);

        ConsoleMenu menu = new ConsoleMenu(app, infra, authService);
        menu.run();

        AppLogger.info("app", "MiniBank terminated");
    }

    /**
     * Ensures demo data and demo users exist in the SQL database.
     * Creates a demo customer, account, beneficiary and two demo users if necessary.
     */
    private static void ensureDemoDataSql(Bootstrap infra, PasswordEncoder encoder) {
        CustomerRepository customers = infra.customers;
        AccountRepository accounts = infra.accounts;
        UserRepository users = infra.users;

        UnitOfWork uow = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            int cid = 1;
            var existing = customers.byId(cid);

            if (existing.isPresent()) {
                cid = existing.get().id();
            } else {
                cid = customers.nextId();
                Customer c = new Customer(
                        cid,
                        "Alice Customer",
                        "alice@example.com",
                        new Address("Hlavni 1", "Ostrava")
                );
                customers.save(c);

                int accId = accounts.nextId();
                Account a = new Account(
                        accId,
                        new IBAN("CZ6508000000192000145399"),
                        Money.czk(20_000),
                        Money.czk(5_000)
                );
                accounts.save(a);

                c.addAccountId(accId);
                customers.save(c);

                int bid = customers.nextBeneficiaryId();
                Beneficiary b = new Beneficiary(
                        bid,
                        "Bob Receiver",
                        new IBAN("CZ0201000000000012345678"),
                        false
                );
                customers.saveBeneficiary(cid, b);

                System.out.println(
                        "[Bootstrap SQL] Demo data created: customer=" + cid
                                + ", account=" + accId
                                + ", beneficiary=" + bid
                );
            }

            ensureDemoUsers(users, encoder, cid);

            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /**
     * Ensures that demo users for customer login and fraud analyst login exist.
     */
    private static void ensureDemoUsers(UserRepository users,
                                        PasswordEncoder encoder,
                                        int customerId) {
        if (users.findByUsername("alice").isEmpty()) {
            int uid = users.nextId();
            byte[] salt = encoder.generateSalt();
            byte[] hash = encoder.hash("alice123".toCharArray(), salt);
            User alice = new User(uid, "alice", hash, salt, UserRole.CUSTOMER, customerId);
            users.save(alice);
        }

        if (users.findByUsername("fraud").isEmpty()) {
            int uid = users.nextId();
            byte[] salt = encoder.generateSalt();
            byte[] hash = encoder.hash("fraud123".toCharArray(), salt);
            User fraud = new User(uid, "fraud", hash, salt, UserRole.FRAUD_ANALYST, null);
            users.save(fraud);
        }
    }
}
