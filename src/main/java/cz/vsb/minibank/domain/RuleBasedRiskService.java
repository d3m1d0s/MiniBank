package cz.vsb.minibank.domain;


import cz.vsb.minibank.domain.value.Money;


public class RuleBasedRiskService implements RiskService {

    private static final Money AUTH_THRESHOLD_FOR_UNTRUSTED = Money.czk(5_000.00);
    private static final Money ALERT_THRESHOLD_FOR_UNTRUSTED = Money.czk(10_000.00);

    @Override
    public RiskDecision evaluate(boolean beneficiaryTrusted, Money amount, Money dailyLimit) {
        boolean overDailyLimit = amount.gt(dailyLimit);
        boolean untrustedAndHigh = !beneficiaryTrusted && amount.gt(AUTH_THRESHOLD_FOR_UNTRUSTED);

        boolean requireAuth  = overDailyLimit || untrustedAndHigh;
        boolean createAlert  = !beneficiaryTrusted && amount.gt(ALERT_THRESHOLD_FOR_UNTRUSTED);

        String reason = createAlert
                ? "New beneficiary + high amount"
                : (requireAuth ? "Above daily limit or untrusted+high amount" : null);

        return new RiskDecision(requireAuth, createAlert, reason);
    }
}
