package cz.vsb.minibank.domain;


public record RiskDecision(boolean requireAuthorization, boolean createFraudAlert, String reason) {
    public static RiskDecision noRisk() { return new RiskDecision(false, false, null); }
}