package cz.vsb.minibank.infrastructure.json.dto;

import java.math.BigDecimal;

/**
 * JSON representation of a transfer including authorization metadata.
 */
public class JsonTransfer {
    public int id;
    public int sourceAccountId;
    public Integer beneficiaryId;
    public String targetIbanSnapshot;

    /** Held as the domain holds it. See {@link JsonAccount#balance} for why it is not a double. */
    public BigDecimal amount;

    /**
     * The currency the amount above was stored in.
     *
     * Load-bearing rather than decorative, and it stays here although the domain field beside
     * {@code Transfer.amount} went: this is the persistence record, and the SQL backend keeps the
     * same fact in its own {@code currency} column, now under a CHECK. Dropping it here would
     * leave one adapter recording what a stored amount is denominated in and the other not, which
     * is the divergence this change exists to close rather than one to open. It is read back into
     * the {@code Money} on load, so a row written by hand in anything but crowns is refused by
     * {@code Transfer}'s constructor instead of quietly becoming that many crowns.
     */
    public String currency;

    /**
     * The fee actually charged, or null while the transfer has not settled.
     *
     * Nullable on purpose, so "charged nothing" and "not charged yet" stay different facts - a
     * ZeroFeePolicy really does charge 0.00 and that has to survive the round trip as 0.00 rather
     * than as absent.
     */
    public BigDecimal fee;

    /** The customer's own reference, at most 140 characters. Null when none was given. */
    public String message;

    public String status;
    public String createdAt;

    /** ISO-8601, same shape as createdAt and authValidUntil. Null until the transfer settles. */
    public String settledAt;

    /**
     * What this payment still owes the payment network: the name of a DispatchState constant, or
     * null when it owes it nothing.
     *
     * Null is the ordinary value and not a gap, exactly as it is in the transfers.dispatch_state
     * column: every intra-bank transfer carries it, so does everything that has not settled, and
     * so does every row this store held before the field existed. The mapper refuses a name it
     * cannot read rather than treating it as absent, because absent is the lenient reading and
     * would drop a settled payment out of the sweep that owes it a dispatch.
     */
    public String dispatchState;

    public String authMethod;
    public String cardNumberMasked;
    public String declineReason;

    public Integer authAttempts;
    public String authValidUntil;
}
