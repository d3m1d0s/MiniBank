package cz.vsb.minibank.infrastructure.sql.repo;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.sql.SqlUnitOfWork;
import cz.vsb.minibank.infrastructure.uow.IdentityMapAccounts;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.math.BigDecimal;
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
        String sql = "SELECT id, iban, balance_czk, daily_limit_czk FROM accounts WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }

                int dbId = rs.getInt("id");
                String ibanStr = rs.getString("iban");
                BigDecimal balance = rs.getBigDecimal("balance_czk");
                BigDecimal limit = rs.getBigDecimal("daily_limit_czk");

                Account acc = new Account(
                        dbId,
                        new IBAN(ibanStr),
                        Money.czk(balance),
                        Money.czk(limit)
                );

                if (uow != null) {
                    uow.put(Account.class, dbId, acc);
                }

                return Optional.of(acc);
            }
        }
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
        String sql = "SELECT id, iban, balance_czk, daily_limit_czk FROM accounts WHERE iban = ?";
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
                if (uow != null) {
                    Account cached = uow.get(Account.class, dbId);
                    if (cached != null) {
                        return Optional.of(cached);
                    }
                }
                String ibanStr = rs.getString("iban");
                BigDecimal balance = rs.getBigDecimal("balance_czk");
                BigDecimal limit = rs.getBigDecimal("daily_limit_czk");

                Account acc = new Account(
                        dbId,
                        new IBAN(ibanStr),
                        Money.czk(balance),
                        Money.czk(limit)
                );

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
                upsertAccount(sqlUow.connection(), account);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to save account id=" + account.id(), e);
            }
        });

        uow.put(Account.class, account.id(), account);
    }

    /**
     * Inserts or updates an account row.
     * The customer_id column is intentionally not updated on conflict;
     * it is managed by {@code SqlCustomerRepository} based on Customer.accountIds().
     */
    private void upsertAccount(Connection conn, Account account) throws SQLException {
        String sql = """
            INSERT INTO accounts (id, iban, balance_czk, daily_limit_czk, customer_id)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE
              SET iban = EXCLUDED.iban,
                  balance_czk = EXCLUDED.balance_czk,
                  daily_limit_czk = EXCLUDED.daily_limit_czk
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, account.id());
            ps.setString(2, account.iban().value());
            ps.setBigDecimal(3, account.balance().amount());
            ps.setBigDecimal(4, account.dailyLimit().amount());

            // At insert time we do not know the owner yet; keep it NULL.
            // SqlCustomerRepository will later assign customer_id via UPDATE.
            ps.setNull(5, java.sql.Types.INTEGER);

            ps.executeUpdate();
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
                SELECT id, iban, balance_czk, daily_limit_czk
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

                    String ibanStr = rs.getString("iban");
                    BigDecimal balance = rs.getBigDecimal("balance_czk");
                    BigDecimal limit = rs.getBigDecimal("daily_limit_czk");

                    Account acc = new Account(
                            dbId,
                            new IBAN(ibanStr),
                            Money.czk(balance),
                            Money.czk(limit)
                    );

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
