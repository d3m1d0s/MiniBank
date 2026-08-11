package cz.vsb.minibank.infrastructure.sql.repo;

import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.infrastructure.sql.SqlUnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.sql.*;
import java.util.List;
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

    /**
     * Buffers the customer row and the ownership claim its account ids make.
     *
     * The ids are read here, at the call, rather than inside the mutation that runs at commit,
     * and the difference is the whole reason the claim below can be strict. The aggregate goes
     * on being edited in between: DemoScenario opens its accounts after this first save and
     * appends their ids to this very list, so a mutation reading the list late would claim rows
     * whose INSERTs are still queued behind it and match nothing. Reading now is also the
     * behaviour DemoScenario already describes when it calls this list "empty on the first save"
     * and relies on the second save to write ownership.
     */
    @Override
    public void save(Customer c) {
        UnitOfWork uow = UowContext.current();
        if (!(uow instanceof SqlUnitOfWork sqlUow)) {
            throw new IllegalStateException("Customer mutations must be executed inside a SQL UnitOfWork");
        }

        List<Integer> ownedAccountIds = c.accountIds().stream().sorted().toList();

        uow.registerMutation(() -> {
            try {
                upsertCustomer(sqlUow.connection(), c, ownedAccountIds);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to save customer id=" + c.id(), e);
            }
        });

        // Keep Identity Map in sync
        uow.put(Customer.class, c.id(), c);
    }

    private void upsertCustomer(Connection conn, Customer c, List<Integer> ownedAccountIds)
            throws SQLException {

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
        //
        // This is the second writer of accounts rows, next to SqlAccountRepository's upsert,
        // and it takes the same row locks. It writes the one column that upsert deliberately
        // never SETs, so neither can clobber the other and neither touches version - but the
        // lock ORDER matters, and that is why the ids arrive sorted. saveBothInIdOrder takes
        // its two account locks strictly ascending; a customer whose accountIds happened to be
        // stored descending would take them the other way round and the two writers could
        // deadlock. Today every accountIds list is built ascending, so the sort changes nothing
        // and exists so that staying safe does not depend on that continuing to be true.
        if (ownedAccountIds.isEmpty()) {
            return;
        }

        String accSql = "UPDATE accounts SET customer_id = ? WHERE id = ?";
        try (PreparedStatement psAcc = conn.prepareStatement(accSql)) {
            for (Integer accId : ownedAccountIds) {
                psAcc.setInt(1, c.id());
                psAcc.setInt(2, accId);
                psAcc.addBatch();
            }
            refuseUnclaimedAccounts(c.id(), ownedAccountIds, psAcc.executeBatch());
        }
    }

    /**
     * Fails the transaction when an ownership claim matched no row.
     *
     * accounts.customer_id is the whole of ownership in SQL mode: SqlAccountRepository inserts
     * it NULL and never sets it again, and Customer.accountIds() is read back out of it. An
     * UPDATE that matches nothing therefore leaves the account owned by nobody, the customer
     * reloading with no accounts, and every money path answering 404 - which is what DemoScenario
     * warns about. This used to happen in silence, because the counts executeBatch answers with
     * were thrown away and a zero-row UPDATE is not an error to JDBC.
     *
     * Throwing is safe here and not merely loud. The call runs inside a mutation buffered by
     * SqlUnitOfWork.commit, which rolls the JDBC transaction back on any RuntimeException, so the
     * customers row this same method wrote a moment ago goes back with it and the caller is left
     * with nothing rather than with half an ownership.
     *
     * SUCCESS_NO_INFO is skipped rather than counted as zero. It is the driver saying it ran the
     * statement but cannot report how many rows it touched, which is not the same as none, and
     * refusing a correct write over it would be worse than the defect being fixed. PostgreSQL
     * reports real counts for a batched UPDATE, so that arm exists for the JDBC contract rather
     * than for the driver in use. Everything else has to be exactly one row: the predicate is the
     * primary key, so no statement in this batch can honestly touch two.
     */
    private static void refuseUnclaimedAccounts(int customerId, List<Integer> accountIds, int[] claimed) {
        for (int i = 0; i < claimed.length; i++) {
            if (claimed[i] == 1 || claimed[i] == Statement.SUCCESS_NO_INFO) {
                continue;
            }
            throw new DataIntegrityException(
                    "Customer " + customerId + " claims account " + accountIds.get(i)
                            + ", which has no accounts row to own");
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
