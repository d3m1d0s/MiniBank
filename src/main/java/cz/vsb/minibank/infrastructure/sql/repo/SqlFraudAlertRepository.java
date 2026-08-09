package cz.vsb.minibank.infrastructure.sql.repo;

import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertState;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.infrastructure.sql.SqlUnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.StoredValue;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link FraudAlertRepository}.
 * <p>
 * Persists fraud alerts in the {@code fraud_alerts} table and supports
 * UnitOfWork + Identity Map semantics when a {@link SqlUnitOfWork} is active.
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
        // For this project add() and save() share the same upsert semantics.
        save(a);
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

        uow.put(FraudAlert.class, a.id(), a);
    }

    /**
     * Inserts or updates a fraud alert row, including the analyst's verdict and metadata like
     * risk score, assignee, tags (stored as comma-separated text) and notes.
     *
     * The alert lifecycle is completed here: decision and resolved_at have been declared in
     * db/init/schema.sql since the table was created and this statement wrote neither, so an
     * approved alert stored its state and nothing about who decided it or when. decided_by
     * joins them. All three are in the DO UPDATE SET list, because an alert is inserted at NEW
     * and decided by a later save.
     *
     * Tags are joined with commas here and split on commas coming back, which is why
     * FraudApplicationService refuses a comma inside a tag: this column cannot represent one.
     */
    private void upsertAlert(Connection conn, FraudAlert a) throws SQLException {
        String sql = """
            INSERT INTO fraud_alerts
                (id, transfer_id, state, decision, decided_by, reason, risk_score,
                 assignee, tags, notes, created_at, resolved_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                transfer_id = EXCLUDED.transfer_id,
                state       = EXCLUDED.state,
                decision    = EXCLUDED.decision,
                decided_by  = EXCLUDED.decided_by,
                reason      = EXCLUDED.reason,
                risk_score  = EXCLUDED.risk_score,
                assignee    = EXCLUDED.assignee,
                tags        = EXCLUDED.tags,
                notes       = EXCLUDED.notes,
                created_at  = EXCLUDED.created_at,
                resolved_at = EXCLUDED.resolved_at
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, a.id());
            ps.setInt(2, a.transferId());
            ps.setString(3, a.state().name());

            // Null while the alert is open, and decided_by is null for a verdict recorded from
            // the console, which has no login to take an analyst's name from.
            ps.setString(4, a.decision());
            ps.setString(5, a.decidedBy());

            ps.setString(6, a.reason());

            if (a.riskScore() != null) {
                ps.setInt(7, a.riskScore());
            } else {
                ps.setNull(7, Types.INTEGER);
            }

            ps.setString(8, a.assignee());

            String tagsJoined = null;
            if (a.tags() != null && !a.tags().isEmpty()) {
                tagsJoined = String.join(",", a.tags());
            }
            if (tagsJoined != null) {
                ps.setString(9, tagsJoined);
            } else {
                ps.setNull(9, Types.VARCHAR);
            }

            ps.setString(10, a.notes());

            Instant createdAt = a.createdAt();
            if (createdAt != null) {
                ps.setTimestamp(11, Timestamp.from(createdAt));
            } else {
                ps.setNull(11, Types.TIMESTAMP_WITH_TIMEZONE);
            }

            Instant resolvedAt = a.resolvedAt();
            if (resolvedAt != null) {
                ps.setTimestamp(12, Timestamp.from(resolvedAt));
            } else {
                ps.setNull(12, Types.TIMESTAMP_WITH_TIMEZONE);
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
        SELECT id,
               transfer_id,
               state,
               decision,
               decided_by,
               reason,
               risk_score,
               assignee,
               tags,
               notes,
               created_at,
               resolved_at
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
        SELECT id,
               transfer_id,
               state,
               decision,
               decided_by,
               reason,
               risk_score,
               assignee,
               tags,
               notes,
               created_at,
               resolved_at
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
        SELECT id,
               transfer_id,
               state,
               decision,
               decided_by,
               reason,
               risk_score,
               assignee,
               tags,
               notes,
               created_at,
               resolved_at
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

    /**
     * Maps a single {@link ResultSet} row to a {@link FraudAlert} including
     * metadata (risk score, assignee, tags, notes).
     */
    private FraudAlert mapRowToAlert(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        int transferId = rs.getInt("transfer_id");
        String reason = rs.getString("reason");
        String stateStr = rs.getString("state");
        Timestamp ts = rs.getTimestamp("created_at");
        Instant createdAt = (ts != null ? ts.toInstant() : null);

        Integer riskScore = (Integer) rs.getObject("risk_score");
        String assignee = rs.getString("assignee");
        String tagsText = rs.getString("tags");
        String notes = rs.getString("notes");

        java.util.List<String> tags = java.util.Collections.emptyList();
        if (tagsText != null && !tagsText.isBlank()) {
            tags = java.util.Arrays.stream(tagsText.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        FraudAlert alert = new FraudAlert(id, transferId, reason);

        FraudAlertState st = StoredValue.requiredEnum(
                FraudAlertState.class, stateStr, "state", "fraud alert", id);
        alert.hydrateForLoad(st, reason, createdAt, riskScore, assignee, tags, notes);

        // All three are null on every alert written before these columns were wired up, and
        // hydrateDecision accepts that; a loader that refused a legacy row would make every
        // stored alert unreadable. Unlike the state above, absent here is a real value.
        Timestamp resolvedTs = rs.getTimestamp("resolved_at");
        alert.hydrateDecision(
                rs.getString("decision"),
                rs.getString("decided_by"),
                resolvedTs != null ? resolvedTs.toInstant() : null);

        return alert;
    }

}
