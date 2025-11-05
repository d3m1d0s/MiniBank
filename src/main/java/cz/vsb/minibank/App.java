// NOTE: Requires Jackson (jackson-databind). If using Maven, add:
// <dependency>
// <groupId>com.fasterxml.jackson.core</groupId>
// <artifactId>jackson-databind</artifactId>
// <version>2.17.1</version>
// </dependency>


// =========================
// Main entrypoint
// =========================
package cz.vsb.minibank;


import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.FixedOtpValidator;
import cz.vsb.minibank.application.OtpValidator;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.domain.value.*;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.ui.console.ConsoleMenu;


public class App {
    public static void main(String[] args) {
        String dataPath = "data/data.json";
        Bootstrap infra = new Bootstrap(dataPath);
        BootstrapServices app = new BootstrapServices(infra.customers, infra.accounts, infra.transfers, infra.alerts, infra.uowFactory);


        int customerId = ensureDemoData(infra);


        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);
        menu.run();
    }


    private static int ensureDemoData(Bootstrap infra) {
        CustomerRepository customers = infra.customers;
        AccountRepository accounts = infra.accounts;


// Try to load first customer (id=1); if not present, create initial data
        int cid = 1;
        var existing = customers.byId(cid);
        if (existing.isPresent()) return cid;


// Create Customer
        cid = customers.nextId();
        Customer c = new Customer(cid, "Alice Customer", "alice@example.com", new Address("Hlavní 1", "Ostrava"));
        customers.save(c);


// Create Account
        int accId = accounts.nextId();
        Account a = new Account(accId, new IBAN("CZ6508000000192000145399"), Money.czk(20000), Money.czk(5000));
        accounts.save(a);
        c.addAccountId(accId);
        customers.save(c);


// Create Beneficiary
        int bid = customers.nextBeneficiaryId();
        Beneficiary b = new Beneficiary(bid, "Bob Receiver", new IBAN("CZ0201000000000012345678"), false);
        customers.saveBeneficiary(cid, b);


        System.out.println("[Bootstrap] Demo data created: customer=" + cid + ", account=" + accId + ", beneficiary=" + bid);
        return cid;
    }
}