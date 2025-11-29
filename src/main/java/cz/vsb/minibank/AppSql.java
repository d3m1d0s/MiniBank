package cz.vsb.minibank;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import cz.vsb.minibank.ui.console.ConsoleMenu;

public class AppSql {
    public static void main(String[] args) {
        String jdbcUrl = System.getProperty("minibank.jdbcUrl", "jdbc:postgresql://localhost:5432/minibank");
        String dbUser  = System.getProperty("minibank.dbUser",  "minibank");
        String dbPass  = System.getProperty("minibank.dbPass",  "minibank");

        Bootstrap infra = new Bootstrap(jdbcUrl, dbUser, dbPass);
        BootstrapServices app = new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory
        );

        int customerId = ensureDemoDataSql(infra);

        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);
        menu.run();
    }

    private static int ensureDemoDataSql(Bootstrap infra) {
        CustomerRepository customers = infra.customers;
        AccountRepository accounts   = infra.accounts;

        UnitOfWork uow = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            int cid = 1;
            var existing = customers.byId(cid);
            if (existing.isPresent()) {
                uow.commit();
                return cid;
            }

            cid = customers.nextId();
            Customer c = new Customer(
                    cid,
                    "Alice Customer",
                    "alice@example.com",
                    new Address("Hlavní 1", "Ostrava")
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

            uow.commit();
            System.out.println("[Bootstrap SQL] Demo data created: customer=" + cid
                    + ", account=" + accId + ", beneficiary=" + bid);
            return cid;
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }
}
