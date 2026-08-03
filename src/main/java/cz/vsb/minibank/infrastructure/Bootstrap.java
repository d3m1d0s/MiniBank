package cz.vsb.minibank.infrastructure;


import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.repo.*;
import cz.vsb.minibank.domain.DomainEventBus;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.infrastructure.json.JsonUnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.sql.SqlUnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.sql.repo.*;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.domain.repository.UserRepository;
import cz.vsb.minibank.infrastructure.memory.InMemoryUserRepository;
import cz.vsb.minibank.infrastructure.sql.repo.SqlUserRepository;

/**
 * Provides infrastructure dependencies such as repositories and unit of work for JSON and SQL modes.
 */
public class Bootstrap {
    public final JsonDataStore store;
    public final AccountRepository accounts;
    public final CustomerRepository customers;
    public final TransferRepository transfers;
    public final FraudAlertRepository alerts;
    public final UserRepository users;
    public final UnitOfWorkFactory uowFactory;

    /**
     * Who hears about domain events, and the reason there is no static bus any more.
     *
     * One per Bootstrap, handed to the unit of work factory, which hands it to every unit of work
     * it opens. Whatever starts the process registers its observers here; nothing else should,
     * because an observer registered twice writes every audit line twice.
     */
    public final DomainEventBus events = new DomainEventBus();

    /**
     * Creates infrastructure backed by a JSON data store.
     *
     * @param path path to the JSON file used for persistence
     */
    public Bootstrap(String path) {
        this.store = new JsonDataStore(path);
        try {
            // A missing or empty file is a legitimate first run and yields an empty store.
            // Anything else means the file exists but cannot be read, and silently starting
            // empty would overwrite it on the next save.
            this.store.load();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot read the JSON data store at '" + path + "'. "
                            + "Fix the file or move it aside; an absent or empty file starts a new store.", e);
        }
        this.accounts = new JsonAccountRepository(store);
        this.customers = new JsonCustomerRepository(store);
        this.transfers = new JsonTransferRepository(store);
        this.alerts = new JsonFraudAlertRepository(store);
        this.users = new InMemoryUserRepository();
        this.uowFactory = new JsonUnitOfWorkFactory(store, events);
    }

    /**
     * Creates infrastructure backed by PostgreSQL.
     * The JSON store is not used in this mode.
     *
     * @param jdbcUrl JDBC connection URL
     * @param user database user name
     * @param password database user password
     */
    public Bootstrap(String jdbcUrl, String user, String password) {
        this.store = null;

        this.accounts = new SqlAccountRepository(jdbcUrl, user, password);
        this.customers = new SqlCustomerRepository(jdbcUrl, user, password);
        this.transfers = new SqlTransferRepository(jdbcUrl, user, password);
        this.alerts = new SqlFraudAlertRepository(jdbcUrl, user, password);
        this.users = new SqlUserRepository(jdbcUrl, user, password);
        this.uowFactory = new SqlUnitOfWorkFactory(jdbcUrl, user, password, events);
    }
}
