package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.value.Money;

/**
 * Tiered fee policy:
 * free up to 1000 CZK, one percent between 1000 and 10 000 CZK,
 * and one percent plus a fixed fee above that.
 */
public class SimpleFeePolicy implements FeePolicy {

    @Override
    public Money compute(Money amount) {
        Money threshold1 = Money.czk(1000.00);
        Money threshold2 = Money.czk(10_000.00);

        if (amount.compareTo(threshold1) <= 0) {
            return Money.czk(0.00);
        }

        if (amount.compareTo(threshold2) <= 0) {
            return amount.percent(1.0);
        }

        Money percentPart = amount.percent(1.0);
        Money fixedPart = Money.czk(25.00);
        return percentPart.plus(fixedPart);
    }
}
