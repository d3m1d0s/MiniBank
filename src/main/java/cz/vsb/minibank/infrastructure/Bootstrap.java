package cz.vsb.minibank.infrastructure;


import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.repo.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.infrastructure.json.JsonUnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.sql.SqlUnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.sql.repo.*;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;


public class Bootstrap {
    public final JsonDataStore store;
    public final AccountRepository accounts;
    public final CustomerRepository customers;
    public final TransferRepository transfers;
    public final FraudAlertRepository alerts;
    public final UnitOfWorkFactory uowFactory;


    public Bootstrap(String path) {
        this.store = new JsonDataStore(path);
        try { this.store.load(); } catch (Exception ignored) {}
        this.accounts = new JsonAccountRepository(store);
        this.customers = new JsonCustomerRepository(store);
        this.transfers = new JsonTransferRepository(store);
        this.alerts = new JsonFraudAlertRepository(store);
        this.uowFactory = new JsonUnitOfWorkFactory(store);
    }

    public Bootstrap(String jdbcUrl, String user, String password) {
        this.store = null; // not used in SQL mode

        this.accounts = new SqlAccountRepository(jdbcUrl, user, password);
        this.customers = new SqlCustomerRepository(jdbcUrl, user, password);
        this.transfers = new SqlTransferRepository(jdbcUrl, user, password);
        this.alerts = new SqlFraudAlertRepository(jdbcUrl, user, password);

        this.uowFactory = new SqlUnitOfWorkFactory(jdbcUrl, user, password);
    }
}