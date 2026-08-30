package cz.vsb.minibank.infrastructure.sql.repo;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.exceptions.OptimisticLockException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.sql.SqlUnitOfWork;
import cz.vsb.minibank.infrastructure.uow.IdentityMapAccounts;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link AccountRepository}.
 * <p>
 * Uses sequences (accounts_id_seq) for ID generation and either:
 * <ul>
 *     <li>the current {@link SqlUnitOfWork} connection, when a UnitOfWork is active</li>
 *     <li>a short-lived standalone connection otherwise (mainly for tests/tools)</li>
 * </ul>
 */
public final class SqlAccountRepository implements AccountRepository {

    private final String url;
    private final String user;
    private final String password;

    public SqlAccountRepository(String url, String user, String password) {
        this.url = Objects.requireNonNull(url, "url");
        this.user = Objects.requireNonNull(user, "user");
        this.password = Objects.requireNonNull(password, "password");
    }

    @Override
    public int nextId() {
        UnitOfWork uow = UowContext.current();
        if (uow instanceof SqlUnitOfWork sqlUow) {
            try (Statement st = sqlUow.connection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT nextval('accounts_id_seq')")) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                throw new IllegalStateException("Failed to get next account id");
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get next account id", e);
            }
        }

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT nextval('accounts_id_seq')")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            throw new IllegalStateException("Failed to get next account id (no UoW)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get next account id (no UoW)", e);
        }
    }

    @Override
    public Optional<Account> byId(int id) {
        UnitOfWork uow = UowContext.current();
        if (uow != null) {
            Account cached = uow.get(Account.class, id);
            if (cached != null) {
                return Optional.of(cached);
            }
        }

        try {
            if (uow instanceof SqlUnitOfWork sqlUow) {
                return loadByIdWithConnection(sqlUow.connection(), id, uow);
            } else {
                try (Connection conn = DriverManager.getConnection(url, user, password)) {
                    return loadByIdWithConnection(conn, id, uow);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load account by id=" + id, e);
        }
    }

    private Optional<Account> loadByIdWithConnection(Connection conn, int id, UnitOfWork uow) throws SQLException {
        String sql = """
                SELECT id, iban, balance_czk, version
                  FROM accounts
                 WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }

                int dbId = rs.getInt("id");
                Account acc = mapRowToAccount(rs);

                if (uow != null) {
                    uow.put(Account.class, dbId, acc);
                }

                return Optional.of(acc);
            }
        }
    }

    /**
     * Builds the aggregate and records the version this transaction read.
     *
     * The version is put on the instance rather than kept beside it because the identity map
     * already makes this instance the one place a transaction's view of the row lives: every
     * later byId in the same unit of work returns this object rather than a fresh read that
     * would silently refresh the token.
     */
    private static Account mapRowToAccount(ResultSet rs) throws SQLException {
        Account acc = new Account(
                rs.getInt("id"),
                new IBAN(rs.getString("iban")),
                Money.czk(rs.getBigDecimal("balance_czk"))
        );
        acc.hydrateVersion(rs.getInt("version"));
        return acc;
    }

    @Override
    public Optional<Account> byIban(IBAN iban) {
        UnitOfWork uow = UowContext.current();

        // What this transaction already holds comes first, including accounts it has created
        // whose INSERTs are still buffered and therefore invisible to the SELECT below.
        Optional<Account> inFlight = IdentityMapAccounts.byIban(uow, iban);
        if (inFlight.isPresent()) {
            return inFlight;
        }

        try {
            if (uow instanceof SqlUnitOfWork sqlUow) {
                return loadByIbanWithConnection(sqlUow.connection(), iban, uow);
            } else {
                try (Connection conn = DriverManager.getConnection(url, user, password)) {
                    return loadByIbanWithConnection(conn, iban, uow);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load account by IBAN=" + iban, e);
        }
    }

    private Optional<Account> loadByIbanWithConnection(Connection conn, IBAN iban, UnitOfWork uow) throws SQLException {
        String sql = """
                SELECT id, iban, balance_czk, version
                  FROM accounts
                 WHERE iban = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, iban.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }

                int dbId = rs.getInt("id");
                // Same rule as loadByIdWithConnection: one instance per row per unit of work.
                // The probe sits after the query because the row is what supplies the id.
                // accounts.iban is UNIQUE, so there is no second row to disambiguate here.
                //
                // It is also what keeps the version sound: without it this lookup would build a
                // second Account carrying a freshly read version for a row the transaction
                // already holds, and the guarded write would then compare against a token it
                // never actually read.
                if (uow != null) {
                    Account cached = uow.get(Account.class, dbId);
                    if (cached != null) {
                        return Optional.of(cached);
                    }
                }

                Account acc = mapRowToAccount(rs);

                if (uow != null) {
                    uow.put(Account.class, dbId, acc);
                }

                return Optional.of(acc);
            }
        }
    }

    @Override
    public void save(Account account) {
        UnitOfWork uow = UowContext.current();
        if (!(uow instanceof SqlUnitOfWork sqlUow)) {
            throw new IllegalStateException("Account mutations must be executed inside a SQL UnitOfWork");
        }

        uow.registerMutation(() -> {
            try {
                // Only a driver failure is wrapped, and SqlWriteFailure decides which of those
                // is a failure at all: accounts.iban is UNIQUE, and a write that trips it was
                // refused rather than broken. OptimisticLockException is unchecked and
                // deliberately passes through untouched: it is a domain outcome, and wrapping
                // it would have it reported to the customer as INTERNAL_ERROR instead of as the
                // 409 that says nothing was charged.
                upsertAccount(sqlUow.connection(), account);
            } catch (SQLException e) {
                throw SqlWriteFailure.forSave(e, "account", account.id());
            }
        });

        uow.put(Account.class, account.id(), account);
    }

    /**
     * Inserts or updates an account row, refusing a write built on a stale read.
     *
     * This method used to write an absolute balance with no condition, which is the
     * measured leak: twenty concurrent transfers, one debit applied, 115 140 CZK overwritten.
     * The guard is the WHERE on the DO UPDATE arm. When it is false the statement changes
     * nothing and RETURNING yields no row, which is the detection - not a rowcount, because
     * executeUpdate answers 1 for an insert and for an update alike.
     *
     * It holds under READ COMMITTED with no isolation level set anywhere, and that is the
     * point. A racing transaction that has already committed leaves version N+1, so the
     * predicate is false. One that has not committed yet makes this statement block on the row
     * lock; when it commits, PostgreSQL re-reads the latest committed row and evaluates this
     * WHERE against that, not against the snapshot this transaction started with. A plain
     * SELECT would not have seen it. That is what makes a version column enough here and a
     * SELECT ... FOR UPDATE unnecessary.
     *
     * The version is read back rather than incremented in memory because the same account can
     * be saved several times in one unit of work - DemoScenario saves each three times - and
     * only the store knows whether a given execution inserted at 0 or updated to N+1.
     *
     * What it does not catch, stated rather than left to be discovered: it detects a competing
     * UPDATE, not a DELETE. A row removed under this transaction produces no conflict, so the
     * INSERT arm resurrects it with a fresh version and a NULL customer_id. Nothing in this
     * codebase deletes an account - the only route is the ON DELETE CASCADE from customers, and
     * no DELETE FROM exists in src/ or db/ - which is the whole of why that is safe.
     *
     * customer_id is still not updated on conflict; it is managed by SqlCustomerRepository.
     * That is also why its UPDATE needs no version of its own: it writes the one column this
     * statement never writes, so the two cannot overwrite each other.
     */
    private void upsertAccount(Connection conn, Account account) throws SQLException {
        String sql = """
            INSERT INTO accounts (id, iban, balance_czk, customer_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE
              SET iban        = EXCLUDED.iban,
                  balance_czk = EXCLUDED.balance_czk,
                  version     = accounts.version + 1
              WHERE accounts.version = ?
            RETURNING version
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, account.id());
            ps.setString(2, account.iban().value());
            ps.setBigDecimal(3, account.balance().amount());

            // At insert time we do not know the owner yet; keep it NULL.
            // SqlCustomerRepository will later assign customer_id via UPDATE.
            ps.setNull(4, java.sql.Types.INTEGER);

            ps.setInt(5, account.version());

            // version is absent from the INSERT column list on purpose: a new row takes the
            // column default 0, so no code path ever chooses an insert version.
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new OptimisticLockException(
                            "Account " + account.id() + " was changed by another transaction"
                                    + " (this transaction read version " + account.version() + ")");
                }
                account.hydrateVersion(rs.getInt(1));
            }
        }
    }

    @Override
    public List<Account> byCustomerId(int customerId) {
        UnitOfWork uow = UowContext.current();

        try {
            if (uow instanceof SqlUnitOfWork sqlUow) {
                return loadByCustomerIdWithConnection(sqlUow.connection(), customerId, uow);
            } else {
                try (Connection conn = DriverManager.getConnection(url, user, password)) {
                    return loadByCustomerIdWithConnection(conn, customerId, uow);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load accounts for customerId=" + customerId, e);
        }
    }

    private List<Account> loadByCustomerIdWithConnection(Connection conn, int customerId, UnitOfWork uow) throws SQLException {
        String sql = """
                SELECT id, iban, balance_czk, version
                  FROM accounts
                 WHERE customer_id = ?
                """;

        List<Account> result = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int dbId = rs.getInt("id");

                    Account cached = (uow != null) ? uow.get(Account.class, dbId) : null;
                    if (cached != null) {
                        result.add(cached);
                        continue;
                    }

                    Account acc = mapRowToAccount(rs);

                    if (uow != null) {
                        uow.put(Account.class, dbId, acc);
                    }

                    result.add(acc);
                }
            }
        }

        return result;
    }
}
