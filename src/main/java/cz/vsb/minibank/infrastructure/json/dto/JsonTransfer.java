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

    public String authMethod;
    public String cardNumberMasked;
    public String declineReason;

    public Integer authAttempts;
    public String authValidUntil;
}
