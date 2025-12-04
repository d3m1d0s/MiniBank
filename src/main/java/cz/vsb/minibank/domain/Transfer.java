package cz.vsb.minibank.domain;


import cz.vsb.minibank.domain.exceptions.InvalidStateTransitionException;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.domain.lazy.LazyRef;
import java.time.Duration;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.TransferEvents;



import java.time.Instant;

//Audit log
//Validation

public class Transfer {
    private int id;
    private int sourceAccountId;
    private Integer beneficiaryId; // optional snapshot of target
    private String targetIbanSnapshot; // for výpisy


    private Money amount;
    private String currency; // e.g. CZK
    private TransferStatus status;
    private Instant createdAt;
    private Payment authMethod; // nullable
    private String declineReason;

    private int authAttempts;

    private Instant authValidUntil;

    // --- Lazy navigation properties (optional) ---
    private LazyRef<Account> sourceAccountRef;
    private LazyRef<Beneficiary> beneficiaryRef;



    public Transfer(int id, int sourceAccountId, Integer beneficiaryId, String targetIbanSnapshot,
                    Money amount, String currency) {
        this.id = id; this.sourceAccountId = sourceAccountId; this.beneficiaryId = beneficiaryId;
        this.targetIbanSnapshot = targetIbanSnapshot; this.amount = amount; this.currency = currency;
        this.status = TransferStatus.CREATED; this.createdAt = Instant.now();

        this.authAttempts = 0;
        this.authValidUntil = null;
    }


    public Money feeAmount(FeePolicy policy) { return policy.compute(amount); }


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


    public void send(Account source, FeePolicy policy) {
        if (status != TransferStatus.CREATED && status != TransferStatus.WAITING_AUTH)
            throw new InvalidStateTransitionException("Cannot send from status: " + status);

        source.debit(this.amount, feeAmount(policy));

        TransferStatus old = this.status;
        this.status = TransferStatus.SENT;

        TransferEvents.notifyStatusChanged(this, old, this.status);
    }


    public void decline(String reason) {
        if (status == TransferStatus.SENT)
            throw new InvalidStateTransitionException("Cannot decline already SENT transfer");

        TransferStatus old = this.status;
        this.status = TransferStatus.DECLINED;
        this.declineReason = reason;

        TransferEvents.notifyStatusChanged(this, old, this.status);
    }

    public void hydrateForLoad(TransferStatus status,
                               Payment authMethod,
                               String declineReason,
                               Instant createdAt) {
        hydrateForLoad(status, authMethod, declineReason, createdAt, null, null);
    }

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

    public boolean isAuthExpired() {
        return status == TransferStatus.WAITING_AUTH
                && authValidUntil != null
                && Instant.now().isAfter(authValidUntil);
    }

    public void registerFailedOtpAttempt(int maxAttempts) {
        if (status != TransferStatus.WAITING_AUTH) {
            throw new InvalidStateTransitionException("OTP attempts allowed only in WAITING_AUTH");
        }

        this.authAttempts++;

        if (this.authAttempts >= maxAttempts) {
            // финальное отклонение
            decline("Too many invalid OTP attempts");
        }
    }


    // --- Lazy navigation API ---

    public void attachSourceAccount(LazyRef<Account> ref) {
        this.sourceAccountRef = ref;
    }

    public void attachBeneficiary(LazyRef<Beneficiary> ref) {
        this.beneficiaryRef = ref;
    }

    /**
     * Lazily load the source account (may be null if no loader attached).
     */
    public Account sourceAccount() {
        return (sourceAccountRef != null) ? sourceAccountRef.get() : null;
    }

    /**
     * Lazily load the beneficiary (may be null).
     */
    public Beneficiary beneficiary() {
        return (beneficiaryRef != null) ? beneficiaryRef.get() : null;
    }


}