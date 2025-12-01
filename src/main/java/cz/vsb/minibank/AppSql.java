package cz.vsb.minibank;

import cz.vsb.minibank.application.*;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.UserRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import cz.vsb.minibank.ui.console.ConsoleMenu;

public class AppSql {

    public static void main(String[] args) {
        AppLogger.info("app", "Starting MiniBank in SQL mode");


        String jdbcUrl = System.getProperty("minibank.jdbcUrl", "jdbc:postgresql://localhost:5432/minibank");
        String dbUser  = System.getProperty("minibank.dbUser",  "minibank");
        String dbPass  = System.getProperty("minibank.dbPass",  "minibank");

        // Infrastructure + domain services
        Bootstrap infra = new Bootstrap(jdbcUrl, dbUser, dbPass);
        BootstrapServices app = new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory
        );

        // Secure passwords + auth
        PasswordEncoder encoder   = new Pbkdf2PasswordEncoder();
        UserRepository users      = infra.users;
        AuthService authService   = new AuthService(users, encoder);

        // Demo data in SQL + demo users with hash+salt
        ensureDemoDataSql(infra, encoder);

        // Console menu with login and roles
        ConsoleMenu menu = new ConsoleMenu(app, infra, authService);
        menu.run();

        AppLogger.info("app", "MiniBank terminated");
    }

    /**
     * Creates demo customer, account, beneficiary and two users:
     * alice/alice123 (CUSTOMER, associated with this customer)
     * fraud/fraud123 (FRAUD_ANALYST, without customerId).
     */
    private static void ensureDemoDataSql(Bootstrap infra, PasswordEncoder encoder) {
        CustomerRepository customers = infra.customers;
        AccountRepository accounts   = infra.accounts;
        UserRepository users         = infra.users;

        UnitOfWork uow = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            int cid = 1;
            var existing = customers.byId(cid);

            if (existing.isPresent()) {
                // Customer with id=1 already exists - use it to bind user alice
                cid = existing.get().id();
            } else {
                // Create demo customer
                cid = customers.nextId();
                Customer c = new Customer(
                        cid,
                        "Alice Customer",
                        "alice@example.com",
                        new Address("Hlavní 1", "Ostrava")
                );
                customers.save(c);

                // Demo account
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

                // Demo beneficiary
                int bid = customers.nextBeneficiaryId();
                Beneficiary b = new Beneficiary(
                        bid,
                        "Bob Receiver",
                        new IBAN("CZ0201000000000012345678"),
                        false
                );
                customers.saveBeneficiary(cid, b);

                System.out.println("[Bootstrap SQL] Demo data created: customer=" + cid
                        + ", account=" + accId + ", beneficiary=" + bid);
            }

            // Make sure demo users exist in any case
            ensureDemoUsers(users, encoder, cid);

            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    private static void ensureDemoUsers(UserRepository users,
                                        PasswordEncoder encoder,
                                        int customerId) {
        // CUSTOMER: alice / alice123
        if (users.findByUsername("alice").isEmpty()) {
            int uid = users.nextId();
            byte[] salt = encoder.generateSalt();
            byte[] hash = encoder.hash("alice123".toCharArray(), salt);
            User alice = new User(uid, "alice", hash, salt, UserRole.CUSTOMER, customerId);
            users.save(alice);
        }

        // FRAUD_ANALYST: fraud / fraud123
        if (users.findByUsername("fraud").isEmpty()) {
            int uid = users.nextId();
            byte[] salt = encoder.generateSalt();
            byte[] hash = encoder.hash("fraud123".toCharArray(), salt);
            User fraud = new User(uid, "fraud", hash, salt, UserRole.FRAUD_ANALYST, null);
            users.save(fraud);
        }
    }
}
