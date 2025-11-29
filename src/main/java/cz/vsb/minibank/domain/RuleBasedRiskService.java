package cz.vsb.minibank.domain;


import cz.vsb.minibank.domain.value.Money;


public class RuleBasedRiskService implements RiskService {
    @Override
    public RiskDecision evaluate(boolean beneficiaryTrusted, Money amount, Money dailyLimit) {
        boolean requireAuth  = amount.gt(dailyLimit) || !beneficiaryTrusted;
        boolean createAlert  = !beneficiaryTrusted && amount.gt(Money.czk(10000));
        String reason = createAlert
                ? "New beneficiary + high amount"
                : (requireAuth ? "Daily limit exceeded or untrusted beneficiary" : null);
        return new RiskDecision(requireAuth, createAlert, reason);
    }
}