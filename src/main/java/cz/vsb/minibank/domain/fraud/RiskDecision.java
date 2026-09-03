package cz.vsb.minibank.domain.fraud;

/**
 * Result of risk evaluation for a transfer.
 */
public record RiskDecision(boolean requireAuthorization,
                           boolean createFraudAlert,
                           String reason) {

    /**
     * Returns a decision representing low risk with no additional checks.
     */
    public static RiskDecision noRisk() {
        return new RiskDecision(false, false, null);
    }

    /**
     * Derives a numeric risk score for ordering and prioritization.
     */
    public int riskScore() {
        if (!requireAuthorization && !createFraudAlert) {
            return 10;   // low risk
        }
        if (createFraudAlert && requireAuthorization) {
            return 80;   // suspicious and needs authorization
        }
        if (createFraudAlert) {
            return 70;   // suspicious without authorization
        }
        // only authorization, without fraud alert
        return 40;
    }
}
