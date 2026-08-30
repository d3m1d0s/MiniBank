package cz.vsb.minibank.infrastructure.sql.repo;

import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.repository.UserRepository;
import cz.vsb.minibank.infrastructure.sql.SqlUnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.StoredValue;

import java.sql.*;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link UserRepository}.
 * <p>
 * Uses {@code users_id_seq} for id generation and stores credentials
 * (hash + salt) and role/customer mapping. Read operations can use
 * the UnitOfWork Identity Map when present.
 */
public class SqlUserRepository implements UserRepository {

    private final String url;
    private final String user;
    private final String password;

    public SqlUserRepository(String url, String user, String password) {
        this.url = Objects.requireNonNull(url, "url");
        this.user = Objects.requireNonNull(user, "user");
        this.password = Objects.requireNonNull(password, "password");
    }

    @Override
    public int nextId() {
        UnitOfWork uow = UowContext.current();
        if (!(uow instanceof SqlUnitOfWork sqlUow)) {
            throw new IllegalStateException("User id generation must be executed inside a SQL UnitOfWork");
        }

        try (Statement st = sqlUow.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT nextval('users_id_seq')")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            throw new IllegalStateException("Failed to obtain next user id");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to obtain next user id", e);
        }
    }

    @Override
    public Optional<User> byId(int id) {
        UnitOfWork uow = UowContext.current();
        if (uow != null) {
            User cached = uow.get(User.class, id);
            if (cached != null) return Optional.of(cached);
        }

        try {
            if (uow instanceof SqlUnitOfWork sqlUow) {
                return loadById(sqlUow.connection(), id, uow);
            } else {
                try (Connection conn = DriverManager.getConnection(url, user, password)) {
                    return loadById(conn, id, uow);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load user id=" + id, e);
        }
    }


    private Optional<User> loadById(Connection conn, int id, UnitOfWork uow) throws SQLException {
        String sql = """
            SELECT id, username, role, customer_id, password_hash, password_salt
              FROM users
             WHERE id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                User u = mapRow(rs);
                if (uow != null) {
                    uow.put(User.class, u.id(), u);
                }
                return Optional.of(u);
            }
        }
    }

    @Override
    public Optional<User> findByUsername(String username) {
        UnitOfWork uow = UowContext.current();

        try {
            if (uow instanceof SqlUnitOfWork sqlUow) {
                return loadByUsername(sqlUow.connection(), username, uow);
            } else {
                try (Connection conn = DriverManager.getConnection(url, user, password)) {
                    return loadByUsername(conn, username, uow);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load user '" + username + "'", e);
        }
    }

    private Optional<User> loadByUsername(Connection conn, String username, UnitOfWork uow) throws SQLException {
        String sql = """
        SELECT id, username, role, customer_id, password_hash, password_salt
          FROM users
         WHERE username = ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                User u = mapRow(rs);
                if (uow != null) {
                    uow.put(User.class, u.id(), u);
                }
                return Optional.of(u);
            }
        }
    }


    @Override
    public void save(User entity) {
        Objects.requireNonNull(entity, "entity");
        UnitOfWork uow = UowContext.current();
        if (!(uow instanceof SqlUnitOfWork sqlUow)) {
            throw new IllegalStateException("User save must be executed inside a SQL UnitOfWork");
        }

        uow.registerMutation(() -> {
            try {
                // users.username is UNIQUE, and DemoUsersInitializer decides whether to write a
                // row by reading for it first, so two instances starting together can both find
                // it absent. SqlWriteFailure answers the one that loses with a conflict instead
                // of the unexplained failure that shape used to raise.
                upsert(sqlUow.connection(), entity);
            } catch (SQLException e) {
                throw SqlWriteFailure.forSave(e, "user", entity.id());
            }
        });

        uow.put(User.class, entity.id(), entity);
    }

    /**
     * Inserts or updates a user row including credentials and role mapping.
     */
    private void upsert(Connection conn, User u) throws SQLException {
        String sql = """
            INSERT INTO users (id, username, role, customer_id, password_hash, password_salt)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                username = EXCLUDED.username,
                role = EXCLUDED.role,
                customer_id = EXCLUDED.customer_id,
                password_hash = EXCLUDED.password_hash,
                password_salt = EXCLUDED.password_salt
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, u.id());
            ps.setString(2, u.username());
            ps.setString(3, u.role().name());
            if (u.customerId() != null) {
                ps.setInt(4, u.customerId());
            } else {
                ps.setNull(4, Types.INTEGER);
            }
            ps.setBytes(5, u.passwordHash());
            ps.setBytes(6, u.passwordSalt());
            ps.executeUpdate();
        }
    }

    /**
     * Maps a {@link ResultSet} row to a {@link User}, reconstructing role and
     * optional customer id and credential fields.
     */
    private User mapRow(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        String username = rs.getString("username");
        String roleStr = rs.getString("role");
        // Named for the same reason the transfer and alert loaders name theirs, and this one
        // matters most: an unreadable role decides what its holder may do. It used to raise a
        // NullPointerException on an absent value and a bare IllegalArgumentException on an
        // unknown one, both of which the advice answers as an unexplained 500.
        // Locale.ROOT, because this row is read on the sign-in path and the default locale is the
        // JVM's rather than this bank's. Under a Turkish default the i of a row stored as
        // "operations" folds to a capital that keeps its dot, so the result is not the constant
        // OPERATIONS and the holder of a perfectly good account is refused with an unreadable-role
        // fault, on a machine whose only difference is where it thinks it is. Same fix, and the
        // same reason, as IBAN.normalize and the two fraud repositories.
        UserRole role = StoredValue.requiredEnum(
                UserRole.class, roleStr == null ? null : roleStr.toUpperCase(Locale.ROOT),
                "role", "user", id);
        int customerId = rs.getInt("customer_id");
        Integer customerIdObj = rs.wasNull() ? null : customerId;
        byte[] hash = rs.getBytes("password_hash");
        byte[] salt = rs.getBytes("password_salt");
        return new User(id, username, hash, salt, role, customerIdObj);
    }
}
