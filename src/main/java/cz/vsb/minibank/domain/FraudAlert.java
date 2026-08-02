package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.exceptions.InvalidStateTransitionException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Domain model representing a fraud alert attached to a transfer.
 */
public class FraudAlert {

    private final int id;
    private final int transferId;

    private FraudAlertState state;
    private String reason;
    private Instant createdAt;

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
     */
    public void approve() {
        if (state != FraudAlertState.NEW) {
            throw new InvalidStateTransitionException(
                    "Only an open alert can be approved, this one is " + state);
        }

        FraudAlertState old = this.state;
        this.state = FraudAlertState.OK;
        FraudAlertEvents.notifyStateChanged(this, old, this.state);
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
     */
    public void markSuspicious(String reason) {
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

        FraudAlertEvents.notifyStateChanged(this, old, this.state);
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

    public Integer riskScore() { return riskScore; }
    public String assignee() { return assignee; }
    public List<String> tags() { return Collections.unmodifiableList(tags); }
    public String notes() { return notes; }
}
