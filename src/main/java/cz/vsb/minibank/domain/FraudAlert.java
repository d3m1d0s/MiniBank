package cz.vsb.minibank.domain;

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
     * Marks the alert as OK and publishes a state change event.
     */
    public void approve() {
        FraudAlertState old = this.state;
        this.state = FraudAlertState.OK;
        FraudAlertEvents.notifyStateChanged(this, old, this.state);
    }

    /**
     * Marks the alert as suspicious with the given reason and publishes a state change event.
     */
    public void markSuspicious(String reason) {
        FraudAlertState old = this.state;
        this.state = FraudAlertState.SUSPICIOUS;
        this.reason = reason;
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
