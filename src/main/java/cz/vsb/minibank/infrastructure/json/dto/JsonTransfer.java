package cz.vsb.minibank.infrastructure.json.dto;

/**
 * JSON representation of a transfer including authorization metadata.
 */
public class JsonTransfer {
    public int id;
    public int sourceAccountId;
    public Integer beneficiaryId;
    public String targetIbanSnapshot;
    public double amount;
    public String currency;

    /**
     * The fee actually charged, or null while the transfer has not settled.
     *
     * Boxed, so "charged nothing" and "not charged yet" stay different facts - a ZeroFeePolicy
     * really does charge 0.00 and that has to survive the round trip as 0.00, not as absent.
     */
    public Double fee;

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
