package cz.vsb.minibank.infrastructure.sql.repo;

import cz.vsb.minibank.domain.CardPayment;
import cz.vsb.minibank.domain.Payment;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.exceptions.TransferChangedException;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.sql.SqlUnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.StoredValue;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link TransferRepository}.
 * <p>
 * Persists transfers in the {@code transfers} table and stores authorization
 * metadata (auth method, masked card, OTP attempts, expiry) alongside business
 * fields.
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
        // For this project we treat add() and save() the same (UPSERT).
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
                // Only a driver failure is wrapped. TransferChangedException is unchecked and
                // deliberately passes through untouched: it is a domain outcome, and wrapping it
                // would have it reported as INTERNAL_ERROR instead of as the 409 that tells the
                // customer to look at the payment again.
                upsertTransfer(sqlUow.connection(), t);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to save transfer id=" + t.id(), e);
            }
        });

        // Keep Identity Map in sync
        uow.put(Transfer.class, t.id(), t);
    }

    /**
     * Inserts or updates a transfer row including the charged fee, the customer's reference,
     * the settlement instant and the authorization metadata, refusing a write built on a stale
     * read.
     *
     * fee, message and settled_at are in the DO UPDATE SET list and not only in the INSERT, and
     * that is load-bearing: a transfer is inserted at CREATED and settled by a later save, so
     * an insert-only fee would never be written at all and the stored fee would be inert on this backend.
     *
     * The guard is the WHERE on the DO UPDATE arm, the same shape
     * {@code SqlAccountRepository.upsertAccount} carries and for the same reasons - it holds
     * under READ COMMITTED with no isolation level set anywhere, and the detection is the empty
     * RETURNING rather than a rowcount, because executeUpdate answers 1 for an insert and for an
     * update alike. SqlAccountRepository explains why in full and that comment is not repeated here.
     *
     * What it adds over the account's version is the row it covers. Every column above is assigned unconditionally,
     * so before this guard existed a cancel committing just after an authorization wrote
     * DECLINED over SENT and blanked fee and settled_at with it: the debit stood while the row
     * dropped out of the SENT-only day total. Three writers reach this method without ever
     * calling accounts.save - cancelPayment, the wrong-OTP branch and the expired-window branch -
     * which is precisely why accounts.version could not see any of it.
     *
     * A version rather than a status token, deliberately. A status token would not catch
     * WAITING_AUTH to WAITING_AUTH, and that is a real transition: two concurrent wrong-OTP
     * authorizations both read auth_attempts = N and both write N+1, which defeats
     * MAX_OTP_ATTEMPTS.
     */
    private void upsertTransfer(Connection conn, Transfer t) throws SQLException {
        String sql = """
                INSERT INTO transfers (
                    id,
                    source_account_id,
                    beneficiary_id,
                    target_iban_snapshot,
                    amount,
                    currency,
                    fee,
                    message,
                    status,
                    created_at,
                    settled_at,
                    auth_method,
                    card_number_masked,
                    decline_reason,
                    auth_attempts,
                    auth_valid_until
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    source_account_id    = EXCLUDED.source_account_id,
                    beneficiary_id       = EXCLUDED.beneficiary_id,
                    target_iban_snapshot = EXCLUDED.target_iban_snapshot,
                    amount               = EXCLUDED.amount,
                    currency             = EXCLUDED.currency,
                    fee                  = EXCLUDED.fee,
                    message              = EXCLUDED.message,
                    status               = EXCLUDED.status,
                    created_at           = EXCLUDED.created_at,
                    settled_at           = EXCLUDED.settled_at,
                    auth_method          = EXCLUDED.auth_method,
                    card_number_masked   = EXCLUDED.card_number_masked,
                    decline_reason       = EXCLUDED.decline_reason,
                    auth_attempts        = EXCLUDED.auth_attempts,
                    auth_valid_until     = EXCLUDED.auth_valid_until,
                    version              = transfers.version + 1
                WHERE transfers.version = ?
                RETURNING version
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
            ps.setString(6, t.amount().currency());

            // NULL rather than zero on a transfer that has not settled. A stored 0.00 would be
            // a claim that this payment was charged nothing, which is a different fact from
            // "it has not been charged yet"; Transfer.feeFor reads the difference.
            if (t.fee() != null) {
                ps.setBigDecimal(7, t.fee().amount());
            } else {
                ps.setNull(7, Types.NUMERIC);
            }

            if (t.message() != null) {
                ps.setString(8, t.message());
            } else {
                ps.setNull(8, Types.VARCHAR);
            }

            ps.setString(9, t.status().name());
            // Written unconditionally, unlike settled_at below: a transfer always has a creation
            // instant and only sometimes a settlement one. The branch that used to write NULL
            // here was the only way this application could put one in the column, so with it
            // gone every row this code writes carries a timestamp. The column still permits NULL
            // and rows written before this do not change.
            ps.setTimestamp(10, Timestamp.from(t.createdAt()));
            if (t.settledAt() != null) {
                ps.setTimestamp(11, Timestamp.from(t.settledAt()));
            } else {
                ps.setNull(11, Types.TIMESTAMP_WITH_TIMEZONE);
            }
            if (authMethod != null) {
                ps.setString(12, authMethod);
            } else {
                ps.setNull(12, Types.VARCHAR);
            }
            if (cardMask != null) {
                ps.setString(13, cardMask);
            } else {
                ps.setNull(13, Types.VARCHAR);
            }
            if (t.declineReason() != null) {
                ps.setString(14, t.declineReason());
            } else {
                ps.setNull(14, Types.VARCHAR);
            }

            // auth_attempts
            if (t.authAttempts() > 0) {
                ps.setInt(15, t.authAttempts());
            } else {
                ps.setNull(15, Types.INTEGER);
            }

            // auth_valid_until
            if (t.authValidUntil() != null) {
                ps.setTimestamp(16, Timestamp.from(t.authValidUntil()));
            } else {
                ps.setNull(16, Types.TIMESTAMP_WITH_TIMEZONE);
            }

            ps.setInt(17, t.version());

            // version is absent from the INSERT column list on purpose: a new row takes the
            // column default 0, so no code path ever chooses an insert version.
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new TransferChangedException(
                            "Transfer " + t.id() + " was changed by another transaction"
                                    + " (this transaction read version " + t.version() + ")");
                }
                t.hydrateVersion(rs.getInt(1));
            }
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
                   fee,
                   message,
                   status,
                   created_at,
                   settled_at,
                   auth_method,
                   card_number_masked,
                   decline_reason,
                   auth_attempts,
                   auth_valid_until,
                   version
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
               fee,
               message,
               status,
               created_at,
               settled_at,
               auth_method,
               card_number_masked,
               decline_reason,
               auth_attempts,
               auth_valid_until,
               version
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

    @Override
    public Money sentTotalBetween(int accountId, Instant fromInclusive, Instant toExclusive) {
        UnitOfWork uow = UowContext.current();

        try {
            if (uow instanceof SqlUnitOfWork sqlUow) {
                return sumSentWithConnection(sqlUow.connection(), accountId, fromInclusive, toExclusive);
            } else {
                try (Connection conn = DriverManager.getConnection(url, user, password)) {
                    return sumSentWithConnection(conn, accountId, fromInclusive, toExclusive);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to total sent transfers for accountId=" + accountId, e);
        }
    }

    @Override
    public Money sentTotalToIbanBetween(int accountId, String targetIban,
                                        Instant fromInclusive, Instant toExclusive) {
        UnitOfWork uow = UowContext.current();

        try {
            if (uow instanceof SqlUnitOfWork sqlUow) {
                return sumSentToIbanWithConnection(
                        sqlUow.connection(), accountId, targetIban, fromInclusive, toExclusive);
            } else {
                try (Connection conn = DriverManager.getConnection(url, user, password)) {
                    return sumSentToIbanWithConnection(
                            conn, accountId, targetIban, fromInclusive, toExclusive);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to total sent transfers for accountId=" + accountId + " to one payee", e);
        }
    }

    /**
     * The same aggregate as {@link #sumSentWithConnection}, narrowed to one destination.
     *
     * The destination predicate is the only interesting line. It compares NORMALIZED snapshots:
     * whitespace removed, upper case, which is what {@code IBAN.normalize} does in Java and what
     * the two expressions here do in SQL. A plain {@code =} would be wrong rather than merely
     * strict - {@code Transfer} takes its snapshot as a plain String and validates only the
     * amount, so a row holding {@code "cz43 0800 ..."} is reachable through the public
     * constructor, CreditLegTest pins that such a row must still resolve, and an unnormalized
     * comparison would drop it from the total silently instead of failing.
     *
     * It cannot use an index, and that changes nothing: idx_transfers_source_account serves the
     * account equality and everything after it was already a sequential filter over that one
     * account's rows.
     */
    private Money sumSentToIbanWithConnection(Connection conn, int accountId, String targetIban,
                                              Instant fromInclusive, Instant toExclusive)
            throws SQLException {

        String sql = """
        SELECT COALESCE(SUM(amount), 0)
          FROM transfers
         WHERE source_account_id = ?
           AND status = ?
           AND currency = ?
           AND UPPER(REGEXP_REPLACE(target_iban_snapshot, '\\s', '', 'g')) = ?
           AND COALESCE(settled_at, created_at) >= ?
           AND COALESCE(settled_at, created_at) <  ?
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setString(2, TransferStatus.SENT.name());
            ps.setString(3, "CZK");
            ps.setString(4, IBAN.normalize(targetIban));
            ps.setTimestamp(5, Timestamp.from(fromInclusive));
            ps.setTimestamp(6, Timestamp.from(toExclusive));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Money.czk(rs.getBigDecimal(1));
            }
        }
    }

    /**
     * One aggregate row instead of every transfer the account has ever made, and no identity
     * map: the total must be what the store holds, not what this transaction has in memory.
     *
     * The day a payment counts against is the day it settled, which is the day the money
     * actually left. COALESCE is the no-backfill path for rows written before settled_at
     * existed: they keep counting under their creation day, exactly as they did, so applying
     * the migration changes no historical total. A row with neither timestamp counts toward no
     * day at all, because NULL fails both range comparisons - the same rule the JSON backend
     * applies to a timestamp it cannot parse. The currency predicate is there so a row in
     * another currency cannot be summed into a CZK ceiling. It used to be the only thing saying
     * so; transfers_currency_czk now refuses such a row outright, and this stays because the
     * aggregate reads the column without building a Transfer out of any row it counts.
     *
     * idx_transfers_source_account serves the equality predicate and the rest is a sequential
     * filter over that account's own rows, exactly as before - the COALESCE costs nothing in
     * plan terms because the time predicate was never index-served here anyway.
     */
    private Money sumSentWithConnection(Connection conn, int accountId,
                                        Instant fromInclusive, Instant toExclusive) throws SQLException {

        String sql = """
        SELECT COALESCE(SUM(amount), 0)
          FROM transfers
         WHERE source_account_id = ?
           AND status = ?
           AND currency = ?
           AND COALESCE(settled_at, created_at) >= ?
           AND COALESCE(settled_at, created_at) <  ?
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setString(2, TransferStatus.SENT.name());
            ps.setString(3, "CZK");
            ps.setTimestamp(4, Timestamp.from(fromInclusive));
            ps.setTimestamp(5, Timestamp.from(toExclusive));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Money.czk(rs.getBigDecimal(1));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Maps a single {@link ResultSet} row to a {@link Transfer}, including
     * authorization metadata and status restoration.
     */
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
                amount
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
                // Recreate CardPayment exactly as JsonMapper does.
                payment = new CardPayment(amount, cardMask);
            } else {
                payment = new Payment(authMethod, amount) { };
            }
        }

        TransferStatus status = StoredValue.requiredEnum(
                TransferStatus.class, statusStr, "status", "transfer", id);
        t.hydrateForLoad(status, payment, declineReason, createdAt, authAttempts, authValidUntil);

        BigDecimal feeBd = rs.getBigDecimal("fee");
        Timestamp settledTs = rs.getTimestamp("settled_at");
        t.hydrateSettlement(
                feeBd != null ? Money.czk(feeBd) : null,
                settledTs != null ? settledTs.toInstant() : null);
        t.attachMessage(rs.getString("message"));

        // Also outside that catch, and for a sharper reason than the three above: a transfer
        // that arrived here without its version would carry 0 and the next guarded write would
        // be compared against the version of a row nobody has written yet.
        t.hydrateVersion(rs.getInt("version"));

        return t;
    }
}
