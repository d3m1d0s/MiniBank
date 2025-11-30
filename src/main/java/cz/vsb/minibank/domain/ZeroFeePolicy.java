package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.value.Money;

/**
 * FeePolicy special case that always returns zero fee.
 * Useful for demo / test environments where fees are not applied.
 */
public final class ZeroFeePolicy implements FeePolicy {

    @Override
    public Money compute(Money amount) {
        // Keep the same currency but zero amount
        return Money.of(amount.currency(), 0.0);
    }
}
