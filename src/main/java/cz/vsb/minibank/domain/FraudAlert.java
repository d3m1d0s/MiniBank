package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.exceptions.InvalidStateTransitionException;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Domain model representing a fraud alert attached to a transfer.
 */
public class FraudAlert implements RecordsDomainEvents {

    /**
     * What has happened to this alert and has not been published yet. See
     * {@link Transfer#drainDomainEvents()}; the reasoning is the same and is not repeated.
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


    /** The two verdicts an analyst can record. Stored in fraud_alerts.decision. */
    public static final String DECISION_APPROVE = "APPROVE";
    public static final String DECISION_DECLINE = "DECLINE";

    private final int id;
    private final int transferId;

    private FraudAlertState state;
    private String reason;
    private Instant createdAt;

    /**
     * The verdict, who recorded it and when.
     *
     * All three are null while the alert is open, and decidedBy stays null for a decision taken
     * from a surface with no login - the console fraud menu and the demo runner. They are
     * written together, inside the state guard, so a refused transition records nothing.
     */
    private String decision;
    private String decidedBy;
    private Instant resolvedAt;

    private Integer riskScore;
    private String assignee;
    private final List<String> tags = new ArrayList<>();
    private String notes;

    /**
     * The version the store holds for this row, or 0 for an alert no store has seen.
     *
     * The same token {@link Account} and {@link Transfer} carry, and the last aggregate to want
     * one. The state guards below are checks against this transaction's own snapshot and the
     * write is deferred to commit under READ COMMITTED, so between two transactions they catch
     * nothing on their own. What was catching these races was the transfers version, and only by
     * accident: it sees a conflict when both analysts happen to write the transfers row too.
     * Whenever they do not - a DECLINE on a payment that has already been sent or already been
     * declined records the verdict on the alert alone, and the annotate route is a deliberate
     * no-op on both aggregates yet still saves the alert - nothing looked at all. So an APPROVE
     * could overwrite a DECLINE and file confirmed fraud as OK, and an annotation that read the
     * alert as NEW could write NEW, no decision and no resolution instant back over a verdict,
     * reopening a decided alert. That last one is the sharp end: authorizePayment gates on the
     * transfer's status and skips the risk re-check once any alert row exists, so a reopened NEW
     * alert sits on a confirmable payment and nothing holds it again.
     *
     * Only SqlFraudAlertRepository touches it. On the JSON backend it stays 0 forever and nothing
     * reads it, exactly as {@link Transfer#version()} does: JsonFraudAlert declares no such field,
     * and JsonUnitOfWork holds the store lock from its constructor to commit, so a JSON
     * transaction's read and write cannot interleave and there is no stale write for a version to
     * catch.
     */
    private int version;

    public FraudAlert(int id, int transferId, String reason) {
        this(id, transferId, reason, null, null, null, null);
    }

    public FraudAlert(
            int id,
            int transferId,
            String reason,
            Integer riskScore,
            String assignee,
            List<String> tags,
            String notes
    ) {
        this.id = id;
        this.transferId = transferId;
        this.state = FraudAlertState.NEW;
        this.reason = reason;
        this.createdAt = Instant.now();
        this.riskScore = riskScore;
        this.assignee = assignee;
        if (tags != null) {
            this.tags.addAll(tags);
        }
        this.notes = notes;
    }

    /**
     * Populates fields when loading an alert from persistence.
     */
    public void hydrateForLoad(
            FraudAlertState state,
            String reason,
            Instant createdAt,
            Integer riskScore,
            String assignee,
            List<String> tags,
            String notes
    ) {
        this.state = state;
        this.reason = reason;

        // The same rule Transfer states, and stated here too or the two aggregates disagree about
        // what a stored row must carry. The constructor has already stamped Instant.now(), so
        // overwriting only a non-null value gave an alert with no stored creation time the load
        // instant instead - which moves the createdFrom and createdTo filters the analyst's queue
        // runs on, and moves them differently on every read.
        if (createdAt == null) {
            throw new DataIntegrityException(
                    "Stored fraud alert " + id + " has no creation instant");
        }
        this.createdAt = createdAt;

        this.riskScore = riskScore;
        this.assignee = assignee;

        this.tags.clear();
        if (tags != null) {
            this.tags.addAll(tags);
        }

        this.notes = notes;
    }

    public void hydrateForLoad(FraudAlertState state, String reason, Instant createdAt) {
        hydrateForLoad(state, reason, createdAt, null, null, null, null);
    }

    /**
     * Restores the analyst's verdict from a stored row.
     *
     * Separate from hydrateForLoad rather than an eighth, ninth and tenth parameter on its
     * already seven-parameter signature, so its existing call sites - including several in
     * tests - are left alone.
     *
     * All three arguments may be null and none is validated. Every alert that exists today has
     * them absent, so a null check here would make every stored alert fail to load; on the JSON
     * side that failure is swallowed by the mapper and would silently reset a decided queue back
     * to NEW, which on this project's fraud gate makes held transfers unreleasable.
     */
    public void hydrateDecision(String decision, String decidedBy, Instant resolvedAt) {
        this.decision = decision;
        this.decidedBy = decidedBy;
        this.resolvedAt = resolvedAt;
    }

    /**
     * Clears the alert, and with it the transfer it is attached to.
     *
     * Only from NEW. A decided alert cannot be decided again: without this, an APPROVE from a
     * stale queue arriving after a DECLINE would put a confirmed-fraud alert back to OK, and on
     * a transfer that has already been sent there is no transfer-side guard left to stop it -
     * which is exactly what making a negative verdict recordable on a SENT transfer would
     * otherwise reintroduce.
     *
     * The precondition is enforced against whatever the mapper produced, and what the mappers
     * produce is now either the stored state or nothing at all. Both used to wrap the state parse
     * and the hydrate call together in a swallowing catch, so a stored state that did not parse
     * arrived here as the constructor's NEW - a closed alert reopened by a typo in a column - and
     * was accepted. Both now refuse the row instead; see StoredValue.
     *
     * @param decidedBy the analyst's username, or null when the decision came from a surface
     *        with no login - the console fraud menu in legacy JSON mode, and the demo runner.
     *        Null rather than a placeholder: inventing an analyst for a mode with no users
     *        would put a name in an audit record that names nobody.
     * @param decidedAt supplied rather than read off the system clock, like every other instant
     *        this project records
     */
    public void approve(String decidedBy, Instant decidedAt) {
        if (state != FraudAlertState.NEW) {
            throw new InvalidStateTransitionException(
                    "Only an open alert can be approved, this one is " + state);
        }

        FraudAlertState old = this.state;
        this.state = FraudAlertState.OK;

        // Written below the guard, so a refused transition records no verdict, no analyst and
        // no timestamp on an alert somebody else had already decided.
        this.decision = DECISION_APPROVE;
        this.decidedBy = decidedBy;
        this.resolvedAt = java.util.Objects.requireNonNull(decidedAt, "decidedAt");

        raise(new FraudAlertStateChanged(this, old, this.state));
    }

    /**
     * Records confirmed fraud, with the reason the analyst gave.
     *
     * Allowed from OK on purpose, and that is not an oversight: fraud is usually confirmed after
     * the money has left, by which time the alert has been cleared. Refusing it there is what
     * made APPROVE the only verdict a settled transfer would accept.
     *
     * SUSPICIOUS is terminal. There is no way back to OK.
     *
     * The analyst's reason is appended rather than substituted, through {@link #appendReason}.
     * {@code reason} is the only record of why the rules raised this alert at all, and replacing
     * "New beneficiary + high amount" with "Declined by fraud analyst" left a confirmed-fraud case
     * file that no longer said what had been suspicious about the payment.
     *
     * @param decidedBy the analyst's username, or null for a decision recorded from the console
     * @param decidedAt when the verdict was recorded
     */
    public void markSuspicious(String reason, String decidedBy, Instant decidedAt) {
        if (state == FraudAlertState.SUSPICIOUS) {
            throw new InvalidStateTransitionException("This alert is already marked suspicious");
        }

        FraudAlertState old = this.state;
        this.state = FraudAlertState.SUSPICIOUS;

        appendReason(reason);

        // Overwrites an earlier APPROVE, which is right: OK -> SUSPICIOUS is the fraud-confirmed
        // -after-the-fact path, and the decision of record is the last one taken. SUSPICIOUS is
        // terminal, so this can happen at most once.
        this.decision = DECISION_DECLINE;
        this.decidedBy = decidedBy;
        this.resolvedAt = java.util.Objects.requireNonNull(decidedAt, "decidedAt");

        raise(new FraudAlertStateChanged(this, old, this.state));
    }

    /**
     * Adds what an analyst wrote to the case file, keeping everything already in it.
     *
     * The one writer of {@code reason} other than the constructor and the load path, and it exists
     * because until now there was only {@link #markSuspicious}. The decision route carries the
     * analyst's comment on all three verdicts and the two desks label the box as a comment stored
     * with the alert, so on APPROVE and on ANNOTATE the text was parsed, validated and dropped
     * without a word: two buttons out of three silently lost the only thing the analyst typed.
     *
     * Appended and never substituted, for the reason markSuspicious appends. The rules' own
     * sentence is why the alert exists, and an analyst's "beneficiary confirmed by phone" beside it
     * is a second fact about the same case rather than a correction of the first. Unlike the
     * append inside markSuspicious this one can happen more than once, because ANNOTATE is
     * repeatable by design: it is the only route that reaches an already-decided alert. That is
     * accepted rather than guarded, since the alternative is a case file that keeps the first
     * comment and loses every later one, which is the defect being closed here.
     *
     * A null or blank comment writes nothing. Absent is what a decision taken without a comment
     * sends, and appending an empty separator to a case file would make the record longer without
     * making it say more.
     */
    public void appendReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return;
        }
        this.reason = (this.reason == null || this.reason.isBlank())
                ? reason
                : this.reason + " | " + reason;
    }

    public void setRiskScore(Integer riskScore) {
        this.riskScore = riskScore;
    }

    public void assignTo(String assignee) {
        this.assignee = assignee;
    }

    public void replaceTags(List<String> tags) {
        this.tags.clear();
        if (tags != null) {
            this.tags.addAll(tags);
        }
    }

    public void updateNotes(String notes) {
        this.notes = notes;
    }

    public int id() { return id; }
    public int transferId() { return transferId; }
    public FraudAlertState state() { return state; }
    public String reason() { return reason; }
    public Instant createdAt() { return createdAt; }

    /** APPROVE or DECLINE, or null while nobody has decided this alert. */
    public String decision() { return decision; }

    /** The analyst who decided it, or null for a decision taken from a surface with no login. */
    public String decidedBy() { return decidedBy; }

    /** When the verdict was recorded, or null while the alert is open. */
    public Instant resolvedAt() { return resolvedAt; }

    public Integer riskScore() { return riskScore; }
    public String assignee() { return assignee; }
    public List<String> tags() { return Collections.unmodifiableList(tags); }
    public String notes() { return notes; }

    public int version() { return version; }

    /**
     * Records the version the store holds for this alert.
     *
     * Two callers, both in SqlFraudAlertRepository: once when a row is read, and once after a
     * guarded write reports the version it left behind. The second call is what lets one unit of
     * work save the same alert twice without the second write conflicting with the first, and
     * every decision does exactly that - {@code FraudApplicationService.decideAndUpdateAlert}
     * saves once in the verdict arm and again after the assignee, tags and notes block.
     *
     * The invariant a future retry must respect is {@link Account#hydrateVersion}'s: once a save
     * has executed this number is the store's only while that transaction is still going to
     * commit. A rolled-back transaction leaves this instance one ahead of the row, and therefore
     * unusable.
     */
    public void hydrateVersion(int version) {
        this.version = version;
    }
}
