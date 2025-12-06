package cz.vsb.minibank.domain;

public record RiskDecision(boolean requireAuthorization,
                           boolean createFraudAlert,
                           String reason) {

    public static RiskDecision noRisk() {
        return new RiskDecision(false, false, null);
    }


    public int riskScore() {
        if (!requireAuthorization && !createFraudAlert) {
            return 10;   // almost no risk
        }
        if (createFraudAlert && requireAuthorization) {
            return 80;   // suspicious + need authorization
        }
        if (createFraudAlert) {
            return 70;   // suspicious, but without authorization
        }
        // only authorization, without fraudAlert
        return 40;
    }
}
