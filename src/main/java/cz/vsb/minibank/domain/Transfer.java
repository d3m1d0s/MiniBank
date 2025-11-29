package cz.vsb.minibank.domain;


import cz.vsb.minibank.domain.exceptions.InvalidStateTransitionException;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.domain.lazy.LazyRef;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Beneficiary;



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


    // --- Lazy navigation properties (optional) ---
    private LazyRef<Account> sourceAccountRef;
    private LazyRef<Beneficiary> beneficiaryRef;



    public Transfer(int id, int sourceAccountId, Integer beneficiaryId, String targetIbanSnapshot,
                    Money amount, String currency) {
        this.id = id; this.sourceAccountId = sourceAccountId; this.beneficiaryId = beneficiaryId;
        this.targetIbanSnapshot = targetIbanSnapshot; this.amount = amount; this.currency = currency;
        this.status = TransferStatus.CREATED; this.createdAt = Instant.now();
    }


    public Money feeAmount(FeePolicy policy) { return policy.compute(amount); }


    public void requestAuthorization(Payment method) {
        if (status != TransferStatus.CREATED)
            throw new InvalidStateTransitionException("Authorization allowed only from CREATED");
        this.authMethod = method;
        this.status = TransferStatus.WAITING_AUTH;
    }


    public void send(Account source, FeePolicy policy) {
        if (status != TransferStatus.CREATED && status != TransferStatus.WAITING_AUTH)
            throw new InvalidStateTransitionException("Cannot send from status: " + status);
        source.debit(this.amount, feeAmount(policy));
        this.status = TransferStatus.SENT;
    }


    public void decline(String reason) {
        if (status == TransferStatus.SENT)
            throw new InvalidStateTransitionException("Cannot decline already SENT transfer");
        this.status = TransferStatus.DECLINED;
        this.declineReason = reason;
    }

    public void hydrateForLoad(TransferStatus status, Payment authMethod, String declineReason, java.time.Instant createdAt) {
        this.status = status;
        this.authMethod = authMethod;
        this.declineReason = declineReason;
        if (createdAt != null) this.createdAt = createdAt;
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