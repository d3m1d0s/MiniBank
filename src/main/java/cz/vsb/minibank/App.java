package cz.vsb.minibank;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.FixedOtpValidator;
import cz.vsb.minibank.application.OtpValidator;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.ui.console.ConsoleMenu;

/**
 * Console entry point for MiniBank using JSON-based persistence.
 */
public class App {

    public static void main(String[] args) {
        String dataPath = "data/data.json";
        Bootstrap infra = new Bootstrap(dataPath);
        BootstrapServices app = new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory
        );

        int customerId = ensureDemoData(infra);

        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);
        menu.run();
    }

    /**
     * Ensures that a demo customer, account and beneficiary exist in JSON storage.
     * Returns the identifier of the demo customer.
     */
    private static int ensureDemoData(Bootstrap infra) {
        CustomerRepository customers = infra.customers;
        AccountRepository accounts = infra.accounts;

        int cid = 1;
        var existing = customers.byId(cid);
        if (existing.isPresent()) {
            return cid;
        }

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
                Money.czk(20000),
                Money.czk(5000)
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
                "[Bootstrap] Demo data created: customer=" + cid
                        + ", account=" + accId
                        + ", beneficiary=" + bid
        );
        return cid;
    }
}
