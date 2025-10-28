package cz.vsb.minibank.domain;


import cz.vsb.minibank.domain.value.Money;


public class RuleBasedRiskService implements RiskService {
    @Override public RiskDecision evaluate(boolean beneficiaryTrusted, Money amount, Money dailyLimit) {
        boolean auth = amount.gt(dailyLimit) || !beneficiaryTrusted;
        boolean alert = !beneficiaryTrusted && amount.gt(Money.czk(10000));
        String reason = alert ? "Nový příjemce + vysoká částka" : (auth ? "Limit/nový příjemce" : null);
        return new RiskDecision(auth, alert, reason);
    }
}