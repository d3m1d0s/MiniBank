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

    /** What this transfer was actually charged, written once by {@link #send}. Null until then. */
    private Money fee;

    /** The customer's own reference for this payment, at most 140 characters. Nullable. */
    private String message;

    private TransferStatus status;
    private Instant createdAt;

    /** When the money moved. Null on a transfer that has not settled. */
    private Instant settledAt;

    /**
     * What this payment still owes the payment network, or null when it owes it nothing.
     *
     * Written by {@link #send} alone, and only on the leg that leaves the bank, so null covers
     * every transfer that has not settled and every intra-bank one without a constant having to
     * say so. {@link DispatchState} carries the rest of the why.
     *
     * It lives on this row rather than in a table of its own, and that is the whole design: the
     * record that a payment is owed to the network is written in the same unit of work as the
     * debit that owes it, so the two either both commit or neither does. The row that a separate
     * outbox would point at already holds the payload, because the gateway takes this aggregate.
     */
    private DispatchState dispatchState;

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
                    Money amount) {
        this(id, sourceAccountId, beneficiaryId, targetIbanSnapshot, amount, Instant.now());
    }

    /**
     * The same transfer with its creation instant supplied rather than read off the system
     * clock.
     *
     * The daily limit needs this. The daily total is keyed on createdAt, so a service holding a fixed clock
     * that still stamped rows with Instant.now() would query one day and write another: every
     * total would come back zero and the limit would silently never fire. One clock has to
     * decide both, and this is the seam that lets the application service pass it.
     *
     * @param createdAt when this transfer was created; must not be null
     */
    public Transfer(int id, int sourceAccountId, Integer beneficiaryId, String targetIbanSnapshot,
                    Money amount, Instant createdAt) {
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

        // The second half of the same invariant: this bank keeps one currency, and here is where
        // that stops being a convention. Both loaders rebuild the amount from the currency the
        // row was stored with, so a row written by hand in anything else is refused on the way
        // in rather than loaded and then meeting a CZK balance further down as a bare
        // "Currency mismatch" from Money. Same exception type as above and for the same reason:
        // both creation paths build the amount through Money.czkPayment, so no caller can
        // produce a foreign one and this only ever fires on a stored row.
        //
        // The amount is the only place a transfer records its currency now. It used to be here
        // twice - a Money that carries one and a String beside it - and nothing reconciled them,
        // so a stored row could load with the two disagreeing.
        if (!"CZK".equals(amount.currency())) {
            throw new DataIntegrityException(
                    "Transfers are kept in CZK, but this amount is " + amount.currency());
        }

        this.id = id; this.sourceAccountId = sourceAccountId; this.beneficiaryId = beneficiaryId;
        this.targetIbanSnapshot = targetIbanSnapshot; this.amount = amount;
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
    /**
     * Holds a transfer the customer was already free to confirm, because the rules found it
     * suspicious only once an earlier payment to the same payee had settled.
     *
     * A second edge into HELD_FOR_REVIEW, and the reason there was only one before is that the
     * alert rule keyed on a single amount: everything it could catch, it caught at creation. A
     * cumulative rule cannot, because the payment that pushes a payee over the threshold may be
     * created before the one it is being added to has settled. The daily ceiling has had the
     * same shape of second check as the daily limit, and for the same reason.
     *
     * Two things differ from {@link #holdForReview}, and both follow from where this one is
     * reached. The payment method is kept rather than captured: the customer chose it when they
     * submitted, and nothing about a review changes it. And authAttempts is NOT reset - a
     * customer who has already spent two guesses on this transfer has spent them, and a hold
     * that handed them back would make the three-attempt cap something a caller could refill.
     *
     * authValidUntil is cleared, exactly as it is in holdForReview and for its reason: the five
     * minutes are the customer's time to type a code, not the analyst's time to reach a queue.
     */
    public void holdForReviewOnAuthorization() {
        if (status != TransferStatus.WAITING_AUTH)
            throw new InvalidStateTransitionException(
                    "A review hold at authorization is allowed only from WAITING_AUTH");

        TransferStatus old = this.status;
        this.status = TransferStatus.HELD_FOR_REVIEW;
        this.authValidUntil = null;

        raise(new TransferStatusChanged(this, old, this.status));
    }

    public void releaseForAuthorization() {
        if (status != TransferStatus.HELD_FOR_REVIEW)
            throw new InvalidStateTransitionException("Release allowed only from HELD_FOR_REVIEW");

        // authAttempts is deliberately left where it stands, for the reason
        // holdForReviewOnAuthorization gives for not clearing it there: a transfer held at
        // confirmation time can arrive here with guesses already spent, and handing them back
        // would make the three-attempt cap something an analyst's approval refills. Zeroing it
        // used to be defended as a no-op, and that argument was true only while the creation
        // hold was the single edge into HELD_FOR_REVIEW. The creation path still reaches this
        // method with the counter at zero, which is holdForReview's doing and not this one's.

        TransferStatus old = this.status;
        this.status = TransferStatus.WAITING_AUTH;
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

        // The one place that can honestly say a dispatch is owed. The destination has just been
        // resolved and null means the money leaves this bank, which the credit above already
        // depends on; the service that calls this knows the same thing only by asking twice.
        // Recording it here puts the intent in the same unit of work as the debit, so a commit
        // that fails leaves neither a debit nor an obligation to send anything.
        if (destination == null) {
            this.dispatchState = DispatchState.PENDING;
        }

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
     * carrying the spacing a customer typed, matches the account row but not the string. IBAN validation
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
     * DECLINED is refused as firmly as SENT, because the reason recorded here is the record of
     * why this payment stopped and a second decline writes over it. That ran in both directions:
     * an analyst's wording over the customer's own "Canceled by customer", and a customer's
     * Cancel over "Too many invalid OTP attempts" or "Authorization window expired" on a payment
     * they were never given the chance to complete. It also raised a DECLINED to DECLINED status
     * change, so the audit trail carried a transition that never happened.
     * {@code FraudApplicationService} already excludes DECLINED before both of its calls to this
     * method, for exactly that reason; the rule belongs here, where the state machine is, so that
     * no caller can be the one that forgets it.
     *
     * HELD_FOR_REVIEW is deliberately not refused, so a transfer under review is always
     * cancellable by its owner. That is what keeps a customer from being trapped behind a queue
     * nobody is working: the hold has no expiry of its own, so the customer's own Cancel is their
     * way out of it.
     */
    public void decline(String reason) {
        if (status == TransferStatus.SENT)
            throw new InvalidStateTransitionException("Cannot decline already SENT transfer");

        if (status == TransferStatus.DECLINED)
            throw new InvalidStateTransitionException(
                    "Transfer " + id + " has already been declined");

        TransferStatus old = this.status;
        this.status = TransferStatus.DECLINED;
        this.declineReason = reason;

        raise(new TransferStatusChanged(this, old, this.status));
    }

    /**
     * Records that a gateway has been handed this payment.
     *
     * Refused on a transfer that owes the network nothing, which is every intra-bank payment and
     * everything that has not settled: marking one of those dispatched would claim that money left
     * the bank through a gateway that was never given it.
     *
     * A transfer already marked is accepted and simply stays marked. That is not leniency, it is
     * the dispatch contract: send first and mark afterwards is at-least-once, so a retry whose
     * earlier attempt did reach the network and then failed to record it arrives here a second
     * time, and refusing it would turn a successful retry into an error over work already done.
     *
     * No status change and no event: the money moved when this transfer was sent, and who has been
     * handed the payment since is not a lifecycle step the customer sees.
     *
     * @throws InvalidStateTransitionException when this transfer owes the network no dispatch
     */
    public void markDispatched() {
        if (dispatchState == null) {
            throw new InvalidStateTransitionException(
                    "Transfer " + id + " owes the payment network no dispatch");
        }
        this.dispatchState = DispatchState.DISPATCHED;
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

        // A stored row must say when it was created. Overwriting only a non-null value looks
        // defensive and was the opposite of it: the constructor has already stamped
        // Instant.now(), so a row with no creation time quietly became a row created at the
        // moment it was read - an instant that moved on every reload and sorted first in a list
        // that promises the newest. Refused instead, exactly as the constructor refuses an
        // amount or a currency it cannot accept.
        if (createdAt == null) {
            throw new DataIntegrityException(
                    "Stored transfer " + id + " has no creation instant");
        }
        this.createdAt = createdAt;

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
     * Restores what a stored row says this payment owes the network.
     *
     * Its own method for the reason hydrateSettlement is one: the two existing hydrate methods
     * have call sites in tests that must be left alone. Null is what every row written before the
     * column existed carries and what every row that owes nothing carries, and the two are the
     * same fact, which is why nothing here distinguishes them.
     *
     * It validates nothing, exactly like the two above. Whether a stored name is one this domain
     * knows is decided by the loader that read it - both refuse an unreadable one rather than
     * passing null in its place, because null is the lenient reading and would quietly drop a
     * payment out of the sweep that owes it a dispatch.
     */
    public void hydrateDispatch(DispatchState dispatchState) {
        this.dispatchState = dispatchState;
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
     * so a quote is the only honest answer, and it is also what rows written before the fee column have.
     */
    public Money feeFor(FeePolicy policy) {
        return fee != null ? fee : feeAmount(policy);
    }

    /** When the money moved, or null on a transfer that has not settled. */
    public Instant settledAt() { return settledAt; }

    /** What this payment still owes the network, or null when it owes it nothing. */
    public DispatchState dispatchState() { return dispatchState; }

    /** The customer's own reference, or null when none was given. */
    public String message() { return message; }

    public int id() { return id; }
    public int sourceAccountId() { return sourceAccountId; }
    public Integer beneficiaryId() { return beneficiaryId; }
    public String targetIbanSnapshot() { return targetIbanSnapshot; }
    public Money amount() { return amount; }
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
     * guarded write reports the version it left behind. The second call is what would let a
     * later guarded write in the same unit of work be compared against the version the first
     * one left, the way {@link FraudAlert#hydrateVersion}'s is exercised by
     * {@code decideAndUpdateAlert}; no path writes a transfer twice in one unit of work today -
     * {@code routeTransferCreation} registers one save and then settles, and the deferred write
     * picks up the settled state at commit.
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
