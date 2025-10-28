package cz.vsb.minibank.domain;


import cz.vsb.minibank.domain.value.Money;


public class SimpleFeePolicy implements FeePolicy {
    @Override public Money compute(Money amount) {
// 0.5% if amount > 5000 CZK, else 0
        Money threshold = Money.czk(5000);
        if (amount.gt(threshold)) return amount.percent(0.5);
        return Money.czk(0);
    }
}