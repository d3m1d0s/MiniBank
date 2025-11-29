package cz.vsb.minibank.infrastructure.sql.repo;

import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertState;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.infrastructure.sql.SqlUnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL implementation of FraudAlertRepository.
 *
 * Expected table (simplified example):
 *
 *   CREATE TABLE fraud_alerts (
 *       id          INTEGER PRIMARY KEY,
 *       transfer_id INTEGER NOT NULL REFERENCES transfers(id) ON DELETE CASCADE,
 *       state       VARCHAR(32) NOT NULL,
 *       reason      TEXT NOT NULL,
 *       created_at  TIMESTAMPTZ
 *   );
 *
 *   CREATE SEQUENCE fraud_alerts_id_seq;
 */
public final class SqlFraudAlertRepository implements FraudAlertRepository {

    private final String url;
    private final String user;
    private final String password;

    public SqlFraudAlertRepository(String url, String user, String password) {
        this.url = Objects.requireNonNull(url, "url");
        this.user = Objects.requireNonNull(user, "user");
        this.password = Objects.requireNonNull(password, "password");
    }

    // -------------------------------------------------------------------------
    // Ids
    // -------------------------------------------------------------------------

    @Override
    public int nextId() {
        UnitOfWork uow = UowContext.current();
        if (uow instanceof SqlUnitOfWork sqlUow) {
            try (Statement st = sqlUow.connection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT nextval('fraud_alerts_id_seq')")) {
                if (rs.next()) return rs.getInt(1);
                throw new IllegalStateException("Failed to get next fraud alert id");
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get next fraud alert id", e);
            }
        }

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT nextval('fraud_alerts_id_seq')")) {
            if (rs.next()) return rs.getInt(1);
            throw new IllegalStateException("Failed to get next fraud alert id (no UoW)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get next fraud alert id (no UoW)", e);
        }
    }

    // -------------------------------------------------------------------------
    // Add / save
    // -------------------------------------------------------------------------

    @Override
    public void add(FraudAlert a) {
        save(a); // semantics are the same (UPSERT)
    }

    @Override
    public void save(FraudAlert a) {
        UnitOfWork uow = UowContext.current();
        if (!(uow instanceof SqlUnitOfWork sqlUow)) {
            throw new IllegalStateException("FraudAlert mutations must be executed inside a SQL UnitOfWork");
        }

        uow.registerMutation(() -> {
            try {
                upsertAlert(sqlUow.connection(), a);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to save fraud alert id=" + a.id(), e);
            }
        });

        if (uow != null) {
            uow.put(FraudAlert.class, a.id(), a);
        }
    }

    private void upsertAlert(Connection conn, FraudAlert a) throws SQLException {
        String sql = """
                INSERT INTO fraud_alerts (id, transfer_id, state, reason, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    transfer_id = EXCLUDED.transfer_id,
                    state       = EXCLUDED.state,
                    reason      = EXCLUDED.reason,
                    created_at  = EXCLUDED.created_at
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, a.id());
            ps.setInt(2, a.transferId());
            ps.setString(3, a.state().name());
            ps.setString(4, a.reason());
            Instant createdAt = a.createdAt();
            if (createdAt != null) {
                ps.setTimestamp(5, Timestamp.from(createdAt));
            } else {
                ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
            }
            ps.executeUpdate();
        }
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    @Override
    public Optional<FraudAlert> byId(int id) {
        UnitOfWork uow = UowContext.current();
        if (uow != null) {
            FraudAlert cached = uow.get(FraudAlert.class, id);
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
            throw new RuntimeException("Failed to load fraud alert id=" + id, e);
        }
    }

    private Optional<FraudAlert> loadByIdWithConnection(Connection conn, int id, UnitOfWork uow)
            throws SQLException {

        String sql = """
                SELECT id, transfer_id, state, reason, created_at
                  FROM fraud_alerts
                 WHERE id = ?
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                FraudAlert alert = mapRowToAlert(rs);
                if (uow != null) {
                    uow.put(FraudAlert.class, alert.id(), alert);
                }
                return Optional.of(alert);
            }
        }
    }

    @Override
    public Optional<FraudAlert> byTransferId(int transferId) {
        UnitOfWork uow = UowContext.current();

        try {
            if (uow instanceof SqlUnitOfWork sqlUow) {
                return loadByTransferIdWithConnection(sqlUow.connection(), transferId, uow);
            } else {
                try (Connection conn = DriverManager.getConnection(url, user, password)) {
                    return loadByTransferIdWithConnection(conn, transferId, uow);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load fraud alert for transferId=" + transferId, e);
        }
    }

    private Optional<FraudAlert> loadByTransferIdWithConnection(Connection conn, int transferId, UnitOfWork uow)
            throws SQLException {

        String sql = """
            SELECT id, transfer_id, state, reason, created_at
              FROM fraud_alerts
             WHERE transfer_id = ?
             ORDER BY id ASC
             LIMIT 1
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();

                int id = rs.getInt("id");

                // First, check IdentityMap
                if (uow != null) {
                    FraudAlert cached = uow.get(FraudAlert.class, id);
                    if (cached != null) {
                        return Optional.of(cached);
                    }
                }

                // If not cached, map the row and store it in IdentityMap
                FraudAlert alert = mapRowToAlert(rs);

                if (uow != null) {
                    uow.put(FraudAlert.class, alert.id(), alert);
                }

                return Optional.of(alert);
            }
        }
    }

    @Override
    public List<FraudAlert> all() {
        UnitOfWork uow = UowContext.current();

        try {
            if (uow instanceof SqlUnitOfWork sqlUow) {
                return loadAllWithConnection(sqlUow.connection(), uow);
            } else {
                try (Connection conn = DriverManager.getConnection(url, user, password)) {
                    return loadAllWithConnection(conn, uow);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load all fraud alerts", e);
        }
    }

    private List<FraudAlert> loadAllWithConnection(Connection conn, UnitOfWork uow)
            throws SQLException {

        String sql = """
                SELECT id, transfer_id, state, reason, created_at
                  FROM fraud_alerts
                """;

        List<FraudAlert> result = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt("id");
                FraudAlert cached = (uow != null) ? uow.get(FraudAlert.class, id) : null;
                if (cached != null) {
                    result.add(cached);
                    continue;
                }

                FraudAlert alert = mapRowToAlert(rs);
                if (uow != null) {
                    uow.put(FraudAlert.class, alert.id(), alert);
                }
                result.add(alert);
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private FraudAlert mapRowToAlert(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        int transferId = rs.getInt("transfer_id");
        String reason = rs.getString("reason");
        String stateStr = rs.getString("state");
        Timestamp ts = rs.getTimestamp("created_at");
        Instant createdAt = (ts != null ? ts.toInstant() : null);

        FraudAlert alert = new FraudAlert(id, transferId, reason);

        try {
            FraudAlertState st = FraudAlertState.valueOf(stateStr);
            alert.hydrateForLoad(st, reason, createdAt);
        } catch (Exception ignored) {
            // keep whatever constructor set
        }

        return alert;
    }
}
