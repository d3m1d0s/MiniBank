package cz.vsb.minibank.infrastructure.sql.repo;

import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.infrastructure.sql.SqlUnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.sql.*;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link CustomerRepository}.
 * <p>
 * Encapsulates loading and saving customers together with their account ids
 * and beneficiaries, using the same JDBC connection as the active UnitOfWork
 * when available.
 *
 * See class-level Javadoc for expected DB schema.
 */
public final class SqlCustomerRepository implements CustomerRepository {

    private final String url;
    private final String user;
    private final String password;

    public SqlCustomerRepository(String url, String user, String password) {
        this.url = Objects.requireNonNull(url, "url");
        this.user = Objects.requireNonNull(user, "user");
        this.password = Objects.requireNonNull(password, "password");
    }

    // -------------------------------------------------------------------------
    // Customer ids
    // -------------------------------------------------------------------------

    @Override
    public int nextId() {
        UnitOfWork uow = UowContext.current();
        if (uow instanceof SqlUnitOfWork sqlUow) {
            try (Statement st = sqlUow.connection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT nextval('customers_id_seq')")) {
                if (rs.next()) return rs.getInt(1);
                throw new IllegalStateException("Failed to get next customer id");
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get next customer id", e);
            }
        }

        // Fallback when no UoW is active (primarily for tests and tools).
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT nextval('customers_id_seq')")) {
            if (rs.next()) return rs.getInt(1);
            throw new IllegalStateException("Failed to get next customer id (no UoW)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get next customer id (no UoW)", e);
        }
    }

    // -------------------------------------------------------------------------
    // Customer load/save
    // -------------------------------------------------------------------------

    @Override
    public Optional<Customer> byId(int id) {
        UnitOfWork uow = UowContext.current();
        if (uow != null) {
            Customer cached = uow.get(Customer.class, id);
            if (cached != null) return Optional.of(cached);
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
            throw new RuntimeException("Failed to load customer id=" + id, e);
        }
    }

    @Override
    public Optional<Customer> byAccountId(int accountId) {
        UnitOfWork uow = UowContext.current();
        try {
            Integer ownerId;
            if (uow instanceof SqlUnitOfWork sqlUow) {
                ownerId = ownerIdWithConnection(sqlUow.connection(), accountId);
            } else {
                try (Connection conn = DriverManager.getConnection(url, user, password)) {
                    ownerId = ownerIdWithConnection(conn, accountId);
                }
            }
            return ownerId == null ? Optional.empty() : byId(ownerId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load owner of account id=" + accountId, e);
        }
    }

    private Integer ownerIdWithConnection(Connection conn, int accountId) throws SQLException {
        String sql = "SELECT customer_id FROM accounts WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int ownerId = rs.getInt("customer_id");
                return rs.wasNull() ? null : ownerId;
            }
        }
    }

    private Optional<Customer> loadByIdWithConnection(Connection conn, int id, UnitOfWork uow)
            throws SQLException {

        String sql = """
                SELECT id, name, email, street, city
                  FROM customers
                 WHERE id = ?
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();

                int dbId = rs.getInt("id");
                String name = rs.getString("name");
                String email = rs.getString("email");
                String street = rs.getString("street");
                String city = rs.getString("city");

                Customer c = new Customer(
                        dbId,
                        name,
                        email,
                        new Address(street, city)
                );

                // Load account ids for this customer
                String accSql = "SELECT id FROM accounts WHERE customer_id = ?";
                try (PreparedStatement accPs = conn.prepareStatement(accSql)) {
                    accPs.setInt(1, dbId);
                    try (ResultSet accRs = accPs.executeQuery()) {
                        while (accRs.next()) {
                            int accId = accRs.getInt("id");
                            c.addAccountId(accId);
                        }
                    }
                }

                // Load beneficiaries for this customer
                String benSql = """
                        SELECT id, name, iban, trusted
                          FROM beneficiaries
                         WHERE customer_id = ?
                        """;
                try (PreparedStatement benPs = conn.prepareStatement(benSql)) {
                    benPs.setInt(1, dbId);
                    try (ResultSet benRs = benPs.executeQuery()) {
                        while (benRs.next()) {
                            int bid = benRs.getInt("id");
                            String bname = benRs.getString("name");
                            String ibanStr = benRs.getString("iban");
                            boolean trusted = benRs.getBoolean("trusted");

                            Beneficiary b = new Beneficiary(
                                    bid,
                                    bname,
                                    new IBAN(ibanStr),
                                    trusted
                            );
                            c.addBeneficiary(b);
                        }
                    }
                }

                if (uow != null) {
                    uow.put(Customer.class, dbId, c);
                }
                return Optional.of(c);
            }
        }
    }

    @Override
    public void save(Customer c) {
        UnitOfWork uow = UowContext.current();
        if (!(uow instanceof SqlUnitOfWork sqlUow)) {
            throw new IllegalStateException("Customer mutations must be executed inside a SQL UnitOfWork");
        }

        uow.registerMutation(() -> {
            try {
                upsertCustomer(sqlUow.connection(), c);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to save customer id=" + c.id(), e);
            }
        });

        // Keep Identity Map in sync
        uow.put(Customer.class, c.id(), c);
    }

    private void upsertCustomer(Connection conn, Customer c) throws SQLException {
        String sql = """
                INSERT INTO customers (id, name, email, street, city)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE
                  SET name  = EXCLUDED.name,
                      email = EXCLUDED.email,
                      street = EXCLUDED.street,
                      city = EXCLUDED.city
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, c.id());
            ps.setString(2, c.name());
            ps.setString(3, c.email());
            ps.setString(4, c.address().street());
            ps.setString(5, c.address().city());
            ps.executeUpdate();
        }

        // Keep accounts.customer_id in sync with the owning customer.
        String accSql = "UPDATE accounts SET customer_id = ? WHERE id = ?";
        try (PreparedStatement psAcc = conn.prepareStatement(accSql)) {
            for (Integer accId : c.accountIds()) {
                psAcc.setInt(1, c.id());
                psAcc.setInt(2, accId);
                psAcc.addBatch();
            }
            psAcc.executeBatch();
        }
    }

    // -------------------------------------------------------------------------
    // Beneficiaries
    // -------------------------------------------------------------------------

    @Override
    public int nextBeneficiaryId() {
        UnitOfWork uow = UowContext.current();
        if (uow instanceof SqlUnitOfWork sqlUow) {
            try (Statement st = sqlUow.connection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT nextval('beneficiaries_id_seq')")) {
                if (rs.next()) return rs.getInt(1);
                throw new IllegalStateException("Failed to get next beneficiary id");
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get next beneficiary id", e);
            }
        }

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT nextval('beneficiaries_id_seq')")) {
            if (rs.next()) return rs.getInt(1);
            throw new IllegalStateException("Failed to get next beneficiary id (no UoW)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get next beneficiary id (no UoW)", e);
        }
    }

    @Override
    public void saveBeneficiary(int customerId, Beneficiary b) {
        UnitOfWork uow = UowContext.current();
        if (!(uow instanceof SqlUnitOfWork sqlUow)) {
            throw new IllegalStateException("Beneficiary mutations must be executed inside a SQL UnitOfWork");
        }

        // Update aggregate in Identity Map, if present.
        Customer cached = uow.get(Customer.class, customerId);
        if (cached != null) {
            cached.saveBeneficiary(b);
        }

        uow.registerMutation(() -> {
            try {
                upsertBeneficiary(sqlUow.connection(), customerId, b);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to save beneficiary id=" + b.id(), e);
            }
        });
    }

    private void upsertBeneficiary(Connection conn, int customerId, Beneficiary b) throws SQLException {
        String sql = """
                INSERT INTO beneficiaries (id, customer_id, name, iban, trusted)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE
                  SET customer_id = EXCLUDED.customer_id,
                      name        = EXCLUDED.name,
                      iban        = EXCLUDED.iban,
                      trusted     = EXCLUDED.trusted
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, b.id());
            ps.setInt(2, customerId);
            ps.setString(3, b.name());
            ps.setString(4, b.iban().value());
            ps.setBoolean(5, b.trusted());
            ps.executeUpdate();
        }
    }
}
