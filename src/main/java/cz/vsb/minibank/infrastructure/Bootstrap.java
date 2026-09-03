package cz.vsb.minibank.infrastructure;


import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.JsonStoreGuard;
import cz.vsb.minibank.infrastructure.json.repo.*;
import cz.vsb.minibank.domain.event.DomainEventBus;
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
 * In JSON mode it also claims the store file for this process, which is what keeps a second one
 * from starting on the same file and overwriting it.
 */
public class Bootstrap {
    public final JsonDataStore store;

    /**
     * This process's claim on the JSON store file, and null in SQL mode, where the database
     * arbitrates between processes itself.
     *
     * Nothing in the application gives it back: the entry points use their store until the process
     * exits, so it has to stay claimed for all of that time, and the operating system drops the
     * lock when the process ends however it ended. It is closeable for callers that own a lifecycle
     * of their own and open many stores in one JVM.
     */
    public final JsonStoreGuard storeGuard;

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
     * The file is claimed for this process before anything reads it, so a second process over the
     * same store is refused here rather than left to overwrite what the first one committed.
     * {@link JsonStoreGuard} carries the why, and why the answer is a refusal and not a
     * coordination.
     *
     * @param path path to the JSON file used for persistence
     * @throws IllegalStateException if another process already holds this store, or the file exists
     *         and cannot be read
     */
    public Bootstrap(String path) {
        // Before the store is even constructed: loading one sweeps its directory, and every commit
        // afterwards rewrites the whole document from a cache read at startup, so a process that
        // must not be here has to be turned away before it touches any of that.
        this.storeGuard = JsonStoreGuard.acquire(path);
        boolean opened = false;
        try {
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
            opened = true;
        } finally {
            // A Bootstrap that never opened must not leave the store claimed for the rest of the
            // process. An unreadable file is what reaches this, and the next attempt on that path,
            // once the file has been fixed or moved aside, has to find the store free.
            if (!opened) {
                this.storeGuard.close();
            }
        }
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
        // No claim to take: PostgreSQL arbitrates between processes itself, which is the whole
        // reason a second writer is sent here rather than accommodated in the JSON adapter.
        this.storeGuard = null;

        this.accounts = new SqlAccountRepository(jdbcUrl, user, password);
        this.customers = new SqlCustomerRepository(jdbcUrl, user, password);
        this.transfers = new SqlTransferRepository(jdbcUrl, user, password);
        this.alerts = new SqlFraudAlertRepository(jdbcUrl, user, password);
        this.users = new SqlUserRepository(jdbcUrl, user, password);
        this.uowFactory = new SqlUnitOfWorkFactory(jdbcUrl, user, password, events);
    }
}
