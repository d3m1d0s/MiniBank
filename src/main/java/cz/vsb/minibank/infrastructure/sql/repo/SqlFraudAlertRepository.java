package cz.vsb.minibank.infrastructure.sql.repo;

import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertState;
import cz.vsb.minibank.domain.exceptions.FraudAlertChangedException;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.infrastructure.sql.SqlUnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.StoredValue;

import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.value.Money;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                // Only a driver failure is wrapped, and SqlWriteFailure decides which of those is
                // a failure at all: an alert filed on a transfer that already has one is refused
                // by fraud_alerts_one_per_transfer, which is the race the constraint exists for
                // and not a broken server. FraudAlertChangedException is unchecked and
                // deliberately passes through untouched: it is a domain outcome, and wrapping it
                // would have it reported as INTERNAL_ERROR instead of as the 409 that sends the
                // analyst back to the alert.
                upsertAlert(sqlUow.connection(), a);
            } catch (SQLException e) {
                throw SqlWriteFailure.forSave(e, "fraud alert", a.id());
            }
        });

        uow.put(FraudAlert.class, a.id(), a);
    }

    /**
     * Inserts or updates a fraud alert row, including the analyst's verdict and metadata like
     * risk score, assignee, tags (stored as comma-separated text) and notes, refusing a write
     * built on a stale read.
     *
     * The alert lifecycle is completed here: decision and resolved_at have been declared in
     * db/init/schema.sql since the table was created and this statement wrote neither, so an
     * approved alert stored its state and nothing about who decided it or when. decided_by
     * joins them. All three are in the DO UPDATE SET list, because an alert is inserted at NEW
     * and decided by a later save.
     *
     * The guard is the WHERE on the DO UPDATE arm, the same shape
     * {@code SqlAccountRepository.upsertAccount} and {@code SqlTransferRepository.upsertTransfer}
     * carry and for the same reasons - it holds under READ COMMITTED with no isolation level set
     * anywhere, and the detection is the empty RETURNING rather than a rowcount, because
     * executeUpdate answers 1 for an insert and for an update alike. SqlAccountRepository explains
     * why in full and that comment is not repeated here.
     *
     * What it adds over the transfers version is the case where the transfers row is never
     * written. Every column above is assigned unconditionally, so before this guard existed the
     * last analyst to commit simply won: FraudApplicationService skips the transfer write when the
     * payment is already SENT or DECLINED, and its annotate route touches neither aggregate yet
     * still saves the alert. On those paths an APPROVE overwrote a DECLINE, and an annotation that
     * had read the alert as NEW wrote NEW, no decision and no resolved_at back over a verdict -
     * reopening a decided alert, which authorizePayment then lets through because it gates on the
     * transfer's status and skips the risk re-check once any alert row exists.
     *
     * A version rather than a state token, deliberately, and here that is not a refinement but the
     * whole point: the write that reopens a decided alert is the one that changes no state at all.
     *
     * Tags are joined with commas here and split on commas coming back, so a tag containing one
     * would be written as a single tag and read as two. Nothing over HTTP can put a tag in the
     * column any more: the field left the decision request together with the validation rule that
     * used to refuse that character, and the statement keeps writing what the aggregate holds so
     * that anything already stored survives a decision and is still readable on the alert detail.
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
                resolved_at = EXCLUDED.resolved_at,
                version     = fraud_alerts.version + 1
            WHERE fraud_alerts.version = ?
            RETURNING version
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

            ps.setInt(13, a.version());

            // version is absent from the INSERT column list on purpose: a new row takes the
            // column default 0, so no code path ever chooses an insert version, and the WHERE
            // above qualifies the DO UPDATE arm alone and cannot refuse a first insert.
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new FraudAlertChangedException(
                            "Fraud alert " + a.id() + " was changed by another transaction"
                                    + " (this transaction read version " + a.version() + ")");
                }
                a.hydrateVersion(rs.getInt(1));
            }
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
               resolved_at,
               version
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
               resolved_at,
               version
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

    /**
     * The whole analyst queue, in an order this application chooses rather than one the heap
     * happens to hold.
     *
     * The ORDER BY is the only interesting line and it is not decoration. Without it PostgreSQL
     * answers in physical order, and an UPDATE writes a new tuple version wherever there is room,
     * which on a table this size is behind every other row: one annotation on an alert moves it
     * to the end of the queue between two refreshes of the same screen, because
     * FraudController.buildQueue renders what this method returns and sorts nothing itself.
     *
     * id ASC, and not a friendlier order for someone reading the queue, deliberately. It is what
     * the JSON adapter already answers - its list is appended to in id-allocation order and a
     * save replaces the row in place instead of moving it - and the defect being fixed is the two
     * backends listing the same data differently. How the queue ought to be sorted for a human is
     * the paged read's question rather than this one's, and {@link #queuePage} answers it with
     * created_at descending and the id descending behind it.
     */
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
               resolved_at,
               version
          FROM fraud_alerts
         ORDER BY id ASC
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
    // The analyst queue
    // -------------------------------------------------------------------------

    /**
     * One page of the queue in one statement, filtered, ordered and sliced by PostgreSQL.
     *
     * The join is what removed the per-alert query. The queue prints the payment's status and its
     * amount beside every alert, and it filters on both, so those two facts were fetched with a
     * {@code transfers.byId} per row - N+1 statements to draw one screen, and no way to page,
     * because rows were being dropped in Java after the store had already answered with all of
     * them. Joined here, the filters and the slice are one plan and the answer is one page.
     *
     * INNER, so an alert whose payment has gone is left out - which is what the loop it replaced
     * did with the empty Optional, and what the queue needs: the row prints an amount it would not
     * have.
     *
     * ORDER BY created_at DESC, id DESC. The queue used to arrive in id order, which put the
     * freshest work at the bottom. The id tie break is there because offset paging over a partial
     * order repeats one row on the next page and drops another.
     */
    @Override
    public List<QueueRow> queuePage(QueueFilter filter, int offset, int limit) {
        Predicates where = queuePredicates(filter);

        String sql = """
        SELECT a.id,
               a.transfer_id,
               a.state,
               a.decision,
               a.decided_by,
               a.reason,
               a.risk_score,
               a.assignee,
               a.tags,
               a.notes,
               a.created_at,
               a.resolved_at,
               a.version,
               t.status   AS transfer_status,
               t.amount   AS transfer_amount,
               t.currency AS transfer_currency
          FROM fraud_alerts a
          JOIN transfers t ON t.id = a.transfer_id
        """ + where.clause() + """
         ORDER BY a.created_at DESC, a.id DESC
         LIMIT ? OFFSET ?
        """;

        return onConnection("Failed to load the fraud alert queue", (conn, uow) -> {
            List<QueueRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int i = where.bind(ps);
                ps.setInt(i++, Math.max(limit, 0));
                ps.setInt(i, Math.max(offset, 0));

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");

                        // The alert is an aggregate and comes from the identity map when this
                        // unit of work already holds it, so a verdict recorded in this
                        // transaction is the one the queue shows. The payment's two facts are
                        // read straight off the row: no Transfer is built, which is the whole
                        // point of the join.
                        FraudAlert alert = (uow != null) ? uow.get(FraudAlert.class, id) : null;
                        if (alert == null) {
                            alert = mapRowToAlert(rs);
                            if (uow != null) {
                                uow.put(FraudAlert.class, alert.id(), alert);
                            }
                        }

                        TransferStatus status = StoredValue.requiredEnum(
                                TransferStatus.class, rs.getString("transfer_status"),
                                "status", "transfer", rs.getInt("transfer_id"));

                        Money amount = Money.of(rs.getString("transfer_currency"),
                                rs.getBigDecimal("transfer_amount"));

                        rows.add(new QueueRow(alert, rs.getInt("transfer_id"), status, amount));
                    }
                }
            }
            return rows;
        });
    }

    @Override
    public int queueTotal(QueueFilter filter) {
        Predicates where = queuePredicates(filter);

        String sql = """
        SELECT COUNT(*)
          FROM fraud_alerts a
          JOIN transfers t ON t.id = a.transfer_id
        """ + where.clause();

        return onConnection("Failed to count the fraud alert queue", (conn, uow) -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                where.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /**
     * The whole queue by state, in one grouped statement.
     *
     * No join and no filter, deliberately on both counts. These are the counters, and they answer
     * "how much work is there" rather than "what am I looking at" - the page above answers that.
     * The unjoined count also keeps this equal to what counting every stored alert gave before,
     * which is what the desks have always shown.
     */
    @Override
    public Map<FraudAlertState, Integer> countByState() {
        String sql = """
        SELECT state, COUNT(*)
          FROM fraud_alerts
         GROUP BY state
        """;

        return onConnection("Failed to count fraud alerts by state", (conn, uow) -> {
            Map<FraudAlertState, Integer> counts = new EnumMap<>(FraudAlertState.class);
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String stored = rs.getString(1);
                    FraudAlertState st;
                    try {
                        st = FraudAlertState.valueOf(stored);
                    } catch (IllegalArgumentException | NullPointerException e) {
                        throw new DataIntegrityException(
                                "Stored fraud alerts carry an unreadable state: " + stored);
                    }
                    counts.put(st, rs.getInt(2));
                }
            }
            return counts;
        });
    }

    /**
     * Turns a {@link QueueFilter} into a WHERE clause over the joined alert and payment.
     *
     * Three of the six narrow the alert and three narrow the payment behind it, which is why the
     * clause is built over the join rather than over either table alone.
     *
     * The date bounds are inclusive at both ends and the amount bounds are too, because that is
     * what the Java loop this replaced did with its isBefore/isAfter and compareTo pairs. The
     * assignee is a case-insensitive containment test, so an alert with no assignee never matches
     * one - NULL LIKE anything is unknown, and unknown is not kept, exactly as the null check in
     * the loop skipped it.
     */
    private static Predicates queuePredicates(QueueFilter filter) {
        Predicates p = new Predicates();

        if (filter.state() != null) {
            p.add("a.state = ?", filter.state().name());
        }
        if (filter.createdFrom() != null) {
            p.add("a.created_at >= ?", Timestamp.from(filter.createdFrom()));
        }
        if (filter.createdTo() != null) {
            p.add("a.created_at <= ?", Timestamp.from(filter.createdTo()));
        }

        String assignee = filter.assigneeContains();
        if (assignee != null && !assignee.isBlank()) {
            p.add("LOWER(a.assignee) LIKE ? ESCAPE '\\'", "%" + likeFragment(assignee.trim()) + "%");
        }

        if (filter.minAmount() != null) {
            p.add("t.amount >= ?", filter.minAmount());
        }
        if (filter.maxAmount() != null) {
            p.add("t.amount <= ?", filter.maxAmount());
        }

        if (!filter.excludedTransferStatuses().isEmpty()) {
            List<Object> names = new ArrayList<>();
            StringBuilder marks = new StringBuilder();
            for (TransferStatus s : filter.excludedTransferStatuses()) {
                if (!marks.isEmpty()) marks.append(", ");
                marks.append('?');
                names.add(s.name());
            }
            p.add("t.status NOT IN (" + marks + ")", names.toArray());
        }

        return p;
    }

    /**
     * Lower-cases a fragment and defuses the two wildcards LIKE reads, so that a filter typed as
     * text stays a containment test rather than becoming a pattern the analyst did not write.
     */
    private static String likeFragment(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /** A WHERE clause and the values it takes, accumulated one predicate at a time. */
    private static final class Predicates {
        private final StringBuilder sql = new StringBuilder();
        private final List<Object> values = new ArrayList<>();

        void add(String predicate, Object... params) {
            sql.append(sql.isEmpty() ? " WHERE " : " AND ").append(predicate).append('\n');
            values.addAll(List.of(params));
        }

        String clause() {
            return sql.toString();
        }

        /** Binds the accumulated values from position 1 and returns the next free position. */
        int bind(PreparedStatement ps) throws SQLException {
            int i = 1;
            for (Object value : values) {
                ps.setObject(i++, value);
            }
            return i;
        }
    }

    /**
     * Runs a read on the unit of work's connection when there is one, and on a connection of its
     * own when there is not.
     *
     * The same two branches every load above spells out, factored out here because the three queue
     * reads would otherwise repeat them a third, fourth and fifth time. The older methods are left
     * as they are: this is the queue's change, not a rewrite of the file.
     */
    private <T> T onConnection(String failure, SqlRead<T> read) {
        UnitOfWork uow = UowContext.current();
        try {
            if (uow instanceof SqlUnitOfWork sqlUow) {
                return read.apply(sqlUow.connection(), uow);
            }
            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                return read.apply(conn, uow);
            }
        } catch (SQLException e) {
            throw new RuntimeException(failure, e);
        }
    }

    @FunctionalInterface
    private interface SqlRead<T> {
        T apply(Connection conn, UnitOfWork uow) throws SQLException;
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

        // An alert that arrived here without its version would carry 0, and the next guarded
        // write would be compared against the version of a row nobody has written yet.
        alert.hydrateVersion(rs.getInt("version"));

        return alert;
    }

}
