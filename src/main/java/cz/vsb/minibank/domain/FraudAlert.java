package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.exceptions.InvalidStateTransitionException;

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
        if (createdAt != null) {
            this.createdAt = createdAt;
        }
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
     * The precondition is enforced against whatever the mapper produced. Both backends wrap the
     * state parse and the hydrate call together in a swallowing catch
     * (SqlFraudAlertRepository.mapRowToAlert, JsonMapper's alert mapping), so a stored state
     * that does not parse arrives here as the constructor's NEW and is accepted. Making an
     * unknown stored enum fail loudly is its own item and covers the transfer side too.
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
     * The analyst's reason is appended rather than substituted. {@code reason} is the only
     * record of why the rules raised this alert at all, and replacing "New beneficiary + high
     * amount" with "Declined by fraud analyst" left a confirmed-fraud case file that no longer
     * said what had been suspicious about the payment. At most one append can ever happen,
     * because SUSPICIOUS is terminal.
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

        if (reason != null && !reason.isBlank()) {
            this.reason = (this.reason == null || this.reason.isBlank())
                    ? reason
                    : this.reason + " | " + reason;
        }

        // Overwrites an earlier APPROVE, which is right: OK -> SUSPICIOUS is the fraud-confirmed
        // -after-the-fact path, and the decision of record is the last one taken. SUSPICIOUS is
        // terminal, so this can happen at most once.
        this.decision = DECISION_DECLINE;
        this.decidedBy = decidedBy;
        this.resolvedAt = java.util.Objects.requireNonNull(decidedAt, "decidedAt");

        raise(new FraudAlertStateChanged(this, old, this.state));
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
}
