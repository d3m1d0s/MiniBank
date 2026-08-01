package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.exceptions.InvalidAmountException;
import cz.vsb.minibank.domain.exceptions.InvalidStateTransitionException;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.domain.lazy.LazyRef;
import java.time.Duration;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.TransferEvents;

import java.time.Instant;

/**
 * Domain model representing an outgoing transfer with status, authorization and audit data.
 */
public class Transfer {
    private int id;
    private int sourceAccountId;
    private Integer beneficiaryId; // optional snapshot of target beneficiary
    private String targetIbanSnapshot; // IBAN captured at creation time

    private Money amount;
    private String currency; // for example CZK
    private TransferStatus status;
    private Instant createdAt;
    private Payment authMethod; // nullable
    private String declineReason;

    private int authAttempts;

    private Instant authValidUntil;

    // Lazy navigation properties (optional)
    private LazyRef<Account> sourceAccountRef;
    private LazyRef<Beneficiary> beneficiaryRef;

    public Transfer(int id, int sourceAccountId, Integer beneficiaryId, String targetIbanSnapshot,
                    Money amount, String currency) {
        // Class invariant: a transfer always moves a strictly positive amount. This also runs
        // when a stored row is rehydrated, so a row that breaks it is refused as corrupt
        // instead of being loaded back into the domain.
        if (amount == null || !amount.isPositive()) {
            throw new InvalidAmountException("Transfer amount must be greater than zero: " + amount);
        }

        this.id = id; this.sourceAccountId = sourceAccountId; this.beneficiaryId = beneficiaryId;
        this.targetIbanSnapshot = targetIbanSnapshot; this.amount = amount; this.currency = currency;
        this.status = TransferStatus.CREATED; this.createdAt = Instant.now();

        this.authAttempts = 0;
        this.authValidUntil = null;
    }

    /**
     * Computes the fee for this transfer using the given policy.
     */
    public Money feeAmount(FeePolicy policy) { return policy.compute(amount); }

    /**
     * Requests authorization for the transfer and moves it to WAITING_AUTH.
     */
    public void requestAuthorization(Payment method) {
        if (status != TransferStatus.CREATED)
            throw new InvalidStateTransitionException("Authorization allowed only from CREATED");

        TransferStatus old = this.status;

        this.authMethod = method;
        this.status = TransferStatus.WAITING_AUTH;

        this.authAttempts = 0;
        this.authValidUntil = Instant.now().plus(Duration.ofMinutes(5));

        TransferEvents.notifyStatusChanged(this, old, this.status);
    }

    /**
     * Sends the transfer and debits the source account, applying the fee policy.
     */
    public void send(Account source, FeePolicy policy) {
        if (status != TransferStatus.CREATED && status != TransferStatus.WAITING_AUTH)
            throw new InvalidStateTransitionException("Cannot send from status: " + status);

        source.debit(this.amount, feeAmount(policy));

        TransferStatus old = this.status;
        this.status = TransferStatus.SENT;

        TransferEvents.notifyStatusChanged(this, old, this.status);
    }

    /**
     * Declines the transfer with the given reason.
     */
    public void decline(String reason) {
        if (status == TransferStatus.SENT)
            throw new InvalidStateTransitionException("Cannot decline already SENT transfer");

        TransferStatus old = this.status;
        this.status = TransferStatus.DECLINED;
        this.declineReason = reason;

        TransferEvents.notifyStatusChanged(this, old, this.status);
    }

    /**
     * Populates runtime fields when loading from persistence without authorization metadata.
     */
    public void hydrateForLoad(TransferStatus status,
                               Payment authMethod,
                               String declineReason,
                               Instant createdAt) {
        hydrateForLoad(status, authMethod, declineReason, createdAt, null, null);
    }

    /**
     * Populates runtime fields when loading from persistence including authorization metadata.
     */
    public void hydrateForLoad(TransferStatus status,
                               Payment authMethod,
                               String declineReason,
                               Instant createdAt,
                               Integer authAttempts,
                               Instant authValidUntil) {
        this.status = status;
        this.authMethod = authMethod;
        this.declineReason = declineReason;
        if (createdAt != null) this.createdAt = createdAt;
        if (authAttempts != null) this.authAttempts = authAttempts;
        this.authValidUntil = authValidUntil;
    }

    public int id() { return id; }
    public int sourceAccountId() { return sourceAccountId; }
    public Integer beneficiaryId() { return beneficiaryId; }
    public String targetIbanSnapshot() { return targetIbanSnapshot; }
    public Money amount() { return amount; }
    public String currency() { return currency; }
    public TransferStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Payment authMethod() { return authMethod; }
    public String declineReason() { return declineReason; }
    public int authAttempts() { return authAttempts; }
    public Instant authValidUntil() { return authValidUntil; }

    /**
     * Returns true when the transfer is waiting for authorization and the validity window has expired.
     */
    public boolean isAuthExpired() {
        return status == TransferStatus.WAITING_AUTH
                && authValidUntil != null
                && Instant.now().isAfter(authValidUntil);
    }

    /**
     * Registers a failed OTP attempt and declines the transfer when the maximum is reached.
     */
    public void registerFailedOtpAttempt(int maxAttempts) {
        if (status != TransferStatus.WAITING_AUTH) {
            throw new InvalidStateTransitionException("OTP attempts allowed only in WAITING_AUTH");
        }

        this.authAttempts++;

        if (this.authAttempts >= maxAttempts) {
            // final decline after too many invalid OTP attempts
            decline("Too many invalid OTP attempts");
        }
    }

    // Lazy navigation API

    public void attachSourceAccount(LazyRef<Account> ref) {
        this.sourceAccountRef = ref;
    }

    public void attachBeneficiary(LazyRef<Beneficiary> ref) {
        this.beneficiaryRef = ref;
    }

    /**
     * Lazily loads the source account, or returns null if no loader is attached.
     */
    public Account sourceAccount() {
        return (sourceAccountRef != null) ? sourceAccountRef.get() : null;
    }

    /**
     * Lazily loads the beneficiary, or returns null when unavailable.
     */
    public Beneficiary beneficiary() {
        return (beneficiaryRef != null) ? beneficiaryRef.get() : null;
    }
}
