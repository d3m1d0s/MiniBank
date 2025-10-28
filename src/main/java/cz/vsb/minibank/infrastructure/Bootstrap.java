package cz.vsb.minibank.infrastructure;


import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.repo.*;
import cz.vsb.minibank.domain.repository.*;


public class Bootstrap {
    public final JsonDataStore store;
    public final AccountRepository accounts;
    public final CustomerRepository customers;
    public final TransferRepository transfers;
    public final FraudAlertRepository alerts;


    public Bootstrap(String path) {
        this.store = new JsonDataStore(path);
        try { this.store.load(); } catch (Exception ignored) {}
        this.accounts = new JsonAccountRepository(store);
        this.customers = new JsonCustomerRepository(store);
        this.transfers = new JsonTransferRepository(store);
        this.alerts = new JsonFraudAlertRepository(store);
    }
}