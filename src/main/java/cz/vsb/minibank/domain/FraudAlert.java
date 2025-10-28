package cz.vsb.minibank.domain;


import java.time.Instant;


public class FraudAlert {
    private int id;
    private int transferId;
    private FraudAlertState state;
    private String reason;
    private Instant createdAt;


    public FraudAlert(int id, int transferId, String reason) {
        this.id = id; this.transferId = transferId; this.reason = reason;
        this.state = FraudAlertState.NEW; this.createdAt = Instant.now();
    }

    public void hydrateForLoad(FraudAlertState state, String reason, java.time.Instant createdAt) {
        this.state = state;
        this.reason = reason;
        if (createdAt != null) {
            this.createdAt = createdAt;
        }
    }


    public void approve() { this.state = FraudAlertState.OK; }
    public void markSuspicious(String reason) { this.state = FraudAlertState.SUSPICIOUS; this.reason = reason; }


    public int id() { return id; }
    public int transferId() { return transferId; }
    public FraudAlertState state() { return state; }
    public String reason() { return reason; }
    public Instant createdAt() { return createdAt; }
}