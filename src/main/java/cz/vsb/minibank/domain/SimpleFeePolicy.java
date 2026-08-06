package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.value.Money;

/**
 * Tiered fee charged on the whole amount: free up to 1 000.00 CZK, one percent up to
 * 10 000.00 CZK, and one percent plus a flat 25.00 CZK above that.
 *
 * Both boundaries belong to the cheaper tier, which is the part a reader is most likely to get
 * wrong: 1 000.00 is free and 10 000.00 pays one percent.
 *
 * It is a step function on the whole amount rather than marginal brackets, so the tiers are
 * discontinuous. One heller past 1 000.00 turns a free payment into a 10.00 fee, and one heller
 * past 10 000.00 adds 25.00 to it. That is the tariff rather than an oversight - marginal
 * brackets would be a different product, not a bug fix - and {@code SimpleFeePolicyTest} pins
 * both boundaries so that moving one is a deliberate act.
 */
public class SimpleFeePolicy implements FeePolicy {

    /** Free up to and including this amount. */
    private static final Money FREE_UP_TO = Money.czk(1_000.00);

    /** One percent up to and including this amount; above it the surcharge is added as well. */
    private static final Money PERCENT_ONLY_UP_TO = Money.czk(10_000.00);

    private static final Money SURCHARGE = Money.czk(25.00);

    private static final double PERCENT = 1.0;

    @Override
    public Money compute(Money amount) {
        if (amount.compareTo(FREE_UP_TO) <= 0) {
            return Money.czk(0.00);
        }

        if (amount.compareTo(PERCENT_ONLY_UP_TO) <= 0) {
            return amount.percent(PERCENT);
        }

        return amount.percent(PERCENT).plus(SURCHARGE);
    }
}
