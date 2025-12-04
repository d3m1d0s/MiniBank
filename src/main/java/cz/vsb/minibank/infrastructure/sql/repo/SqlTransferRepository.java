package cz.vsb.minibank.infrastructure.sql.repo;

import cz.vsb.minibank.domain.CardPayment;
import cz.vsb.minibank.domain.Payment;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.sql.SqlUnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL implementation of TransferRepository.
 *
 * Expected table:
 *
 *   CREATE TABLE transfers (
 *       id                 INTEGER PRIMARY KEY,
 *       source_account_id  INTEGER NOT NULL REFERENCES accounts(id),
 *       beneficiary_id     INTEGER REFERENCES beneficiaries(id),
 *       target_iban_snapshot VARCHAR(34) NOT NULL,
 *       amount             NUMERIC(14,2) NOT NULL,
 *       currency           VARCHAR(3) NOT NULL,
 *       status             VARCHAR(32) NOT NULL,
 *       created_at         TIMESTAMPTZ,
 *       auth_method        VARCHAR(32),
 *       card_number_masked VARCHAR(64),
 *       decline_reason     TEXT
 *   );
 *
 *   CREATE SEQUENCE transfers_id_seq;
 */
public final class SqlTransferRepository implements TransferRepository {

    private final String url;
    private final String user;
    private final String password;

    public SqlTransferRepository(String url, String user, String password) {
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
                 ResultSet rs = st.executeQuery("SELECT nextval('transfers_id_seq')")) {
                if (rs.next()) return rs.getInt(1);
                throw new IllegalStateException("Failed to get next transfer id");
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get next transfer id", e);
            }
        }

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT nextval('transfers_id_seq')")) {
            if (rs.next()) return rs.getInt(1);
            throw new IllegalStateException("Failed to get next transfer id (no UoW)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get next transfer id (no UoW)", e);
        }
    }

    // -------------------------------------------------------------------------
    // Add / save
    // -------------------------------------------------------------------------

    @Override
    public void add(Transfer t) {
        // We treat add/save the same way (UPSERT), semantics are fine for this project.
        save(t);
    }

    @Override
    public void save(Transfer t) {
        UnitOfWork uow = UowContext.current();
        if (!(uow instanceof SqlUnitOfWork sqlUow)) {
            throw new IllegalStateException("Transfer mutations must be executed inside a SQL UnitOfWork");
        }

        uow.registerMutation(() -> {
            try {
                upsertTransfer(sqlUow.connection(), t);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to save transfer id=" + t.id(), e);
            }
        });

        // Keep Identity Map in sync
        uow.put(Transfer.class, t.id(), t);
    }

    private void upsertTransfer(Connection conn, Transfer t) throws SQLException {
        String sql = """
                INSERT INTO transfers (
                    id,
                    source_account_id,
                    beneficiary_id,
                    target_iban_snapshot,
                    amount,
                    currency,
                    status,
                    created_at,
                    auth_method,
                    card_number_masked,
                    decline_reason,
                    auth_attempts,
                    auth_valid_until
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    source_account_id    = EXCLUDED.source_account_id,
                    beneficiary_id       = EXCLUDED.beneficiary_id,
                    target_iban_snapshot = EXCLUDED.target_iban_snapshot,
                    amount               = EXCLUDED.amount,
                    currency             = EXCLUDED.currency,
                    status               = EXCLUDED.status,
                    created_at           = EXCLUDED.created_at,
                    auth_method          = EXCLUDED.auth_method,
                    card_number_masked   = EXCLUDED.card_number_masked,
                    decline_reason       = EXCLUDED.decline_reason,
                    auth_attempts        = EXCLUDED.auth_attempts,
                    auth_valid_until     = EXCLUDED.auth_valid_until
                """;


        Payment auth = t.authMethod();
        String authMethod = null;
        String cardMask = null;
        if (auth != null) {
            authMethod = auth.method();
            if (auth instanceof CardPayment cp) {
                cardMask = cp.cardNumberMasked();
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, t.id());
            ps.setInt(2, t.sourceAccountId());
            if (t.beneficiaryId() != null) {
                ps.setObject(3, t.beneficiaryId(), Types.INTEGER);
            } else {
                ps.setNull(3, Types.INTEGER);
            }
            ps.setString(4, t.targetIbanSnapshot());
            ps.setBigDecimal(5, t.amount().amount());
            ps.setString(6, t.currency());
            ps.setString(7, t.status().name());
            if (t.createdAt() != null) {
                ps.setTimestamp(8, Timestamp.from(t.createdAt()));
            } else {
                ps.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE);
            }
            if (authMethod != null) {
                ps.setString(9, authMethod);
            } else {
                ps.setNull(9, Types.VARCHAR);
            }
            if (cardMask != null) {
                ps.setString(10, cardMask);
            } else {
                ps.setNull(10, Types.VARCHAR);
            }
            if (t.declineReason() != null) {
                ps.setString(11, t.declineReason());
            } else {
                ps.setNull(11, Types.VARCHAR);
            }

            // auth_attempts
            if (t.authAttempts() > 0) {
                ps.setInt(12, t.authAttempts());
            } else {
                ps.setNull(12, Types.INTEGER);
            }

            // auth_valid_until
            if (t.authValidUntil() != null) {
                ps.setTimestamp(13, Timestamp.from(t.authValidUntil()));
            } else {
                ps.setNull(13, Types.TIMESTAMP_WITH_TIMEZONE);
            }


            ps.executeUpdate();
        }
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    @Override
    public Optional<Transfer> byId(int id) {
        UnitOfWork uow = UowContext.current();
        if (uow != null) {
            Transfer cached = uow.get(Transfer.class, id);
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
            throw new RuntimeException("Failed to load transfer id=" + id, e);
        }
    }

    private Optional<Transfer> loadByIdWithConnection(Connection conn, int id, UnitOfWork uow)
            throws SQLException {

        String sql = """
            SELECT id,
                   source_account_id,
                   beneficiary_id,
                   target_iban_snapshot,
                   amount,
                   currency,
                   status,
                   created_at,
                   auth_method,
                   card_number_masked,
                   decline_reason,
                   auth_attempts,
                   auth_valid_until
              FROM transfers
             WHERE id = ?
            """;


        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Transfer t = mapRowToTransfer(rs);
                if (uow != null) {
                    uow.put(Transfer.class, t.id(), t);
                }
                return Optional.of(t);
            }
        }
    }

    @Override
    public List<Transfer> bySourceAccount(int accountId) {
        UnitOfWork uow = UowContext.current();

        try {
            if (uow instanceof SqlUnitOfWork sqlUow) {
                return loadBySourceAccountWithConnection(sqlUow.connection(), accountId, uow);
            } else {
                try (Connection conn = DriverManager.getConnection(url, user, password)) {
                    return loadBySourceAccountWithConnection(conn, accountId, uow);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load transfers for accountId=" + accountId, e);
        }
    }

    private List<Transfer> loadBySourceAccountWithConnection(Connection conn, int accountId, UnitOfWork uow)
            throws SQLException {

        String sql = """
        SELECT id,
               source_account_id,
               beneficiary_id,
               target_iban_snapshot,
               amount,
               currency,
               status,
               created_at,
               auth_method,
               card_number_masked,
               decline_reason,
               auth_attempts,
               auth_valid_until
          FROM transfers
         WHERE source_account_id = ?
        """;


        List<Transfer> result = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    Transfer cached = (uow != null) ? uow.get(Transfer.class, id) : null;
                    if (cached != null) {
                        result.add(cached);
                        continue;
                    }

                    Transfer t = mapRowToTransfer(rs);
                    if (uow != null) {
                        uow.put(Transfer.class, t.id(), t);
                    }
                    result.add(t);
                }
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Transfer mapRowToTransfer(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        int sourceAccountId = rs.getInt("source_account_id");
        Integer beneficiaryId = (Integer) rs.getObject("beneficiary_id");
        String targetIban = rs.getString("target_iban_snapshot");

        BigDecimal amountBd = rs.getBigDecimal("amount");
        String currency = rs.getString("currency");
        Money amount = Money.of(currency, amountBd);

        Transfer t = new Transfer(
                id,
                sourceAccountId,
                beneficiaryId,
                targetIban,
                amount,
                currency
        );

        String statusStr = rs.getString("status");
        Timestamp ts = rs.getTimestamp("created_at");
        Instant createdAt = (ts != null ? ts.toInstant() : null);
        String declineReason = rs.getString("decline_reason");

        String authMethod = rs.getString("auth_method");
        String cardMask = rs.getString("card_number_masked");

        Integer authAttempts = null;
        int attemptsRaw = rs.getInt("auth_attempts");
        if (!rs.wasNull()) authAttempts = attemptsRaw;

        Timestamp validTs = rs.getTimestamp("auth_valid_until");
        Instant authValidUntil = (validTs != null ? validTs.toInstant() : null);

        Payment payment = null;
        if (authMethod != null) {
            if ("CARD".equalsIgnoreCase(authMethod)) {
                // recreate CardPayment exactly as JsonMapper does
                payment = new CardPayment(amount, cardMask);
            } else {
                payment = new Payment(authMethod, amount) { };
            }
        }

        try {
            TransferStatus status = TransferStatus.valueOf(statusStr);
            t.hydrateForLoad(status, payment, declineReason, createdAt, authAttempts, authValidUntil);
        } catch (Exception ignored) {
            // if status is invalid, keep whatever Transfer constructor set
        }

        return t;
    }
}
