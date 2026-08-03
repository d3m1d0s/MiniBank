package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.InvalidIbanException;
import cz.vsb.minibank.domain.exceptions.InvalidStateTransitionException;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.domain.lazy.LazyRef;
import java.time.Duration;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Beneficiary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Domain model representing an outgoing transfer with status, authorization and audit data.
 */
public class Transfer implements RecordsDomainEvents {

    /**
     * What has happened to this transfer and has not been published yet.
     *
     * Not persisted and not part of the aggregate's identity: it is empty on every rehydrated
     * row, because loading a transfer is not something happening to it. Transient in the literal
     * sense, and the unit of work empties it at commit.
     */
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private void raise(DomainEvent event) {
        pendingEvents.add(event);
    }

    @Override
    public List<DomainEvent> drainDomainEvents() {
        List<DomainEvent> drained = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return drained;
    }

    private int id;
    private int sourceAccountId;
    private Integer beneficiaryId; // optional snapshot of target beneficiary
    private String targetIbanSnapshot; // IBAN captured at creation time

    private Money amount;
    private String currency; // for example CZK

    /** What this transfer was actually charged, written once by {@link #send}. Null until then. */
    private Money fee;

    /** The customer's own reference for this payment, at most 140 characters. Nullable. */
    private String message;

    private TransferStatus status;
    private Instant createdAt;

    /** When the money moved. Null on a transfer that has not settled. */
    private Instant settledAt;

    private Payment authMethod; // nullable
    private String declineReason;

    private int authAttempts;

    private Instant authValidUntil;

    /**
     * The version the store holds for this row, or 0 for a transfer no store has seen.
     *
     * The same token {@link Account} carries and for the same reason, kept here rather than in a
     * side map because the identity map already makes this instance a transaction's single view
     * of the row. Only SqlTransferRepository touches it. On the JSON backend it stays 0 forever
     * and nothing reads it: JsonUnitOfWork holds the store lock from its constructor to commit,
     * so a JSON transaction's read and write cannot interleave and there is no stale write for a
     * version to catch.
     *
     * Accounts got one first because the measured leak was on the balance. This row needs its
     * own because three paths write it without ever calling accounts.save - cancelPayment, the
     * wrong-OTP branch and the expired-window branch - so accounts.version cannot see them.
     */
    private int version;

    // Lazy navigation properties (optional)
    private LazyRef<Account> sourceAccountRef;
    private LazyRef<Beneficiary> beneficiaryRef;

    public Transfer(int id, int sourceAccountId, Integer beneficiaryId, String targetIbanSnapshot,
                    Money amount, String currency) {
        this(id, sourceAccountId, beneficiaryId, targetIbanSnapshot, amount, currency, Instant.now());
    }

    /**
     * The same transfer with its creation instant supplied rather than read off the system
     * clock.
     *
     * A9 needs this. The daily total is keyed on createdAt, so a service holding a fixed clock
     * that still stamped rows with Instant.now() would query one day and write another: every
     * total would come back zero and the limit would silently never fire. One clock has to
     * decide both, and this is the seam that lets the application service pass it.
     *
     * @param createdAt when this transfer was created; must not be null
     */
    public Transfer(int id, int sourceAccountId, Integer beneficiaryId, String targetIbanSnapshot,
                    Money amount, String currency, Instant createdAt) {
        // Class invariant: a transfer always moves a strictly positive amount. This also runs
        // when a stored row is rehydrated, so a row that breaks it is refused as corrupt
        // instead of being loaded back into the domain.
        //
        // DataIntegrityException, not InvalidAmountException, because by the time control
        // reaches here on the creation path Money.czkPayment has already rejected every
        // amount a caller can type. In practice this only fires on a corrupt stored row,
        // and a corrupt store must not be reported to the client as its own bad request.
        if (amount == null || !amount.isPositive()) {
            throw new DataIntegrityException("Transfer amount must be greater than zero: " + amount);
        }

        this.id = id; this.sourceAccountId = sourceAccountId; this.beneficiaryId = beneficiaryId;
        this.targetIbanSnapshot = targetIbanSnapshot; this.amount = amount; this.currency = currency;
        this.status = TransferStatus.CREATED;
        this.createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");

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

        raise(new TransferStatusChanged(this, old, this.status));
    }

    /**
     * Holds the transfer for fraud review, ahead of the customer's own confirmation step.
     *
     * authValidUntil is deliberately left null. The five-minute window is the customer's time
     * to enter a code, not the analyst's time to reach the queue; starting it here would expire
     * most held transfers before anyone looked at them, and {@link #isAuthExpired()} reads null
     * as "no deadline" rather than as an expired one.
     *
     * The payment method is captured now, like {@link #requestAuthorization}, so a released
     * transfer presents the same method the customer was shown when they submitted it.
     */
    public void holdForReview(Payment method) {
        if (status != TransferStatus.CREATED)
            throw new InvalidStateTransitionException("Review hold allowed only from CREATED");

        TransferStatus old = this.status;

        this.authMethod = method;
        this.status = TransferStatus.HELD_FOR_REVIEW;

        this.authAttempts = 0;
        this.authValidUntil = null;

        raise(new TransferStatusChanged(this, old, this.status));
    }

    /**
     * Clears a reviewed transfer for the customer's confirmation step. It moves no money: the
     * customer still has to authorize it, and that path re-checks the funds and the daily
     * ceiling.
     *
     * The HELD_FOR_REVIEW precondition is what stops a second analyst on a stale queue
     * releasing a transfer that has already been released, declined or cancelled.
     *
     * authValidUntil stays null, and that is the deliberate part. Starting a five-minute clock
     * here would start it on an event the customer neither causes nor sees - somebody else's
     * click, at a desk, at a time of their choosing. The customer would come back to a payment
     * that had auto-declined, resubmit it, have it held again, and never be able to complete a
     * payment over the alert threshold at all. Nothing is left unguarded by that: the three-OTP
     * cap, the customer's own Cancel, and the funds and daily-ceiling re-checks in
     * TransferApplicationService.authorizePayment all still run at confirmation time. The cost
     * is that a released transfer waits indefinitely; see the note on
     * TransferApplicationService.sentOnTheDayOf for what that does to the day it is counted
     * against.
     */
    public void releaseForAuthorization() {
        if (status != TransferStatus.HELD_FOR_REVIEW)
            throw new InvalidStateTransitionException("Release allowed only from HELD_FOR_REVIEW");

        TransferStatus old = this.status;
        this.status = TransferStatus.WAITING_AUTH;

        // Necessarily already zero - registerFailedOtpAttempt refuses anything but WAITING_AUTH,
        // so a held transfer cannot have spent one - and written anyway so the invariant does
        // not depend on that argument staying true.
        this.authAttempts = 0;
        this.authValidUntil = null;

        raise(new TransferStatusChanged(this, old, this.status));
    }

    /**
     * Settles the transfer: debits the source with the fee applied, and credits the
     * destination when the target IBAN belongs to this bank.
     *
     * The destination is null for a payment that leaves the bank. Resolving it is the
     * caller's job, because looking an IBAN up belongs to a repository; the parameter is
     * mandatory rather than a second overload so that no call site can exist without
     * answering who receives the money. Both legs are here because a transfer that reaches
     * SENT has moved money, and leaving the credit to the callers is what let every call site
     * debit and credit nobody.
     *
     * The three checks below are about stored rows contradicting themselves, not about caller
     * input, which is why they are DataIntegrityException and not a caller-facing refusal: a
     * payment to the source's own IBAN is already refused at creation with a 400, and the
     * other two say the accounts handed over are not the ones this transfer names.
     *
     * HELD_FOR_REVIEW is not in the guard, and that is the backstop under the service-level
     * gate: even if a caller reached this method with a transfer whose alert is still open,
     * the money would not move. A held transfer is settled by first being released.
     *
     * The fee is computed once here and kept. Recomputing it on every display made a settled
     * transfer's charge a function of whichever FeePolicy bean happens to be wired now, so
     * swapping the policy silently restated what customers were charged last month and left
     * every historical balance unexplainable by the numbers shown next to it. {@link #fee()} is
     * what was charged; {@link #feeAmount(FeePolicy)} is still a quote and is what the
     * pre-settlement checks use.
     *
     * @param settledAt when the money moves. Mandatory for the reason {@code destination} is:
     *                  it is the only record of when a payment settled, the daily total is
     *                  keyed on it, and a call site that could omit it would file a payment
     *                  under no day at all. It must be the same instant the caller bounded its
     *                  daily-total window with, or a payment authorized at 23:59:59.999 is
     *                  checked against one day and stamped into the next.
     */
    public void send(Account source, Account destination, FeePolicy policy, Instant settledAt) {
        java.util.Objects.requireNonNull(settledAt, "settledAt");

        if (status != TransferStatus.CREATED && status != TransferStatus.WAITING_AUTH)
            throw new InvalidStateTransitionException("Cannot send from status: " + status);

        if (source.id() != this.sourceAccountId)
            throw new DataIntegrityException("Transfer " + id + " debits account "
                    + sourceAccountId + " but was handed account " + source.id());

        if (destination != null) {
            if (destination.id() == source.id())
                throw new DataIntegrityException(
                        "Transfer " + id + " would credit its own source account " + source.id());
            if (!destination.iban().equals(targetIban()))
                throw new DataIntegrityException("Transfer " + id + " targets " + targetIbanSnapshot
                        + " but was handed account " + destination.id());
        }

        // Debit first: it is the only step that can fail, and it fails before it mutates.
        // Nothing between the two assignments can throw, so no path leaves one leg written.
        //
        // The charged fee is computed once and both debited and stored, so the number kept on
        // the transfer cannot disagree with the number taken out of the account.
        Money charged = feeAmount(policy);
        source.debit(this.amount, charged);
        if (destination != null) {
            destination.credit(this.amount);
        }

        // Written after the only step that can throw and before the status change, so a
        // transfer is never SENT without both, and never carries either without being SENT.
        this.fee = charged;
        this.settledAt = settledAt;

        TransferStatus old = this.status;
        this.status = TransferStatus.SENT;

        raise(new TransferStatusChanged(this, old, this.status));
    }

    /**
     * The target IBAN as a value object.
     *
     * The snapshot is the raw String this transfer was constructed with and is never put back
     * through {@link IBAN} when a stored row is rehydrated, while {@code Account.iban().value()}
     * is always normalized. Comparing the two as text would therefore refuse a destination
     * that was resolved correctly - a snapshot written before IBAN validation existed, or one
     * carrying the spacing a customer typed, matches the account row but not the string. A15
     * gave IBAN equals/hashCode for exactly this comparison and requireDifferentAccount
     * already uses it.
     */
    private IBAN targetIban() {
        try {
            return new IBAN(targetIbanSnapshot);
        } catch (InvalidIbanException e) {
            throw new DataIntegrityException(
                    "Transfer " + id + " carries an unusable target IBAN: " + targetIbanSnapshot);
        }
    }

    /**
     * Declines the transfer with the given reason.
     *
     * Only SENT is refused, so a HELD_FOR_REVIEW transfer is always cancellable by its owner.
     * That is what keeps a customer from being trapped behind a queue nobody is working: the
     * hold has no expiry of its own, so the customer's own Cancel is their way out of it.
     */
    public void decline(String reason) {
        if (status == TransferStatus.SENT)
            throw new InvalidStateTransitionException("Cannot decline already SENT transfer");

        TransferStatus old = this.status;
        this.status = TransferStatus.DECLINED;
        this.declineReason = reason;

        raise(new TransferStatusChanged(this, old, this.status));
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

    /**
     * Restores what a stored row carries that no constructor takes.
     *
     * Separate from hydrateForLoad rather than a seventh and eighth parameter on it, so its two
     * existing overloads and their call sites - including several in tests - are left alone.
     *
     * Both arguments may be null, and must be: every row written before these columns existed
     * has neither. This performs no validation, exactly like hydrateForLoad, because a loader
     * that refused a legacy row would make the whole store unreadable.
     */
    public void hydrateSettlement(Money fee, Instant settledAt) {
        this.fee = fee;
        this.settledAt = settledAt;
    }

    /**
     * Attaches the customer's own reference for this payment.
     *
     * Called by the creating service right after construction and by both mappers on load, not
     * taken by a constructor: Transfer has two constructors with many call sites between them,
     * and a seventh parameter on both would touch every one of them to pass null. Nothing
     * overwrites a message once it is set; the length rule lives at the one validation point in
     * TransferApplicationService, where the console and the demo runner also pass through.
     */
    public void attachMessage(String message) {
        this.message = message;
    }

    /** The fee this transfer was charged, or null while it has not settled. */
    public Money fee() { return fee; }

    /**
     * The fee to show for this transfer: what it was charged if it has settled, and otherwise
     * what the current policy would charge it.
     *
     * Every display site calls this rather than {@link #feeAmount(FeePolicy)}. The fallback is
     * not a second source of truth - a transfer that has not settled has been charged nothing,
     * so a quote is the only honest answer, and it is also what rows written before A14 have.
     */
    public Money feeFor(FeePolicy policy) {
        return fee != null ? fee : feeAmount(policy);
    }

    /** When the money moved, or null on a transfer that has not settled. */
    public Instant settledAt() { return settledAt; }

    /** The customer's own reference, or null when none was given. */
    public String message() { return message; }

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
    public int version() { return version; }

    /**
     * Records the version the store holds for this transfer.
     *
     * Two callers, both in SqlTransferRepository: once when a row is read, and once after a
     * guarded write reports the version it left behind. The second call is what lets the same
     * transfer be saved more than once in one unit of work without the second write conflicting
     * with the first - {@code routeTransferCreation} saves and then settles.
     *
     * The invariant a future retry must respect is {@link Account#hydrateVersion}'s: after a
     * save has executed this number is the store's only while the transaction still commits. A
     * rolled-back transaction leaves this instance one ahead of the row and therefore unusable.
     */
    public void hydrateVersion(int version) {
        this.version = version;
    }

    /**
     * Returns true when the transfer is waiting for authorization and the validity window has expired.
     *
     * A null authValidUntil is no deadline rather than an elapsed one, which is what lets a
     * transfer released from review wait for its owner instead of expiring on them.
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
