package cz.vsb.minibank.domain;


import cz.vsb.minibank.domain.value.Money;


public class SimpleFeePolicy implements FeePolicy {
    @Override
    public Money compute(Money amount) {
        // until 1000 CZK for free
        Money threshold1 = Money.czk(1000.00);
        Money threshold2 = Money.czk(10_000.00);

        if (amount.compareTo(threshold1) <= 0) {
            return Money.czk(0.00);
        }

        // 1000-10 000: 1 %
        if (amount.compareTo(threshold2) <= 0) {
            return amount.percent(1.0);      // 1 %
        }

        // > 10 000: 1 % + 25 CZK
        Money percentPart = amount.percent(1.0);
        Money fixedPart = Money.czk(25.00);
        return percentPart.plus(fixedPart);
    }
}