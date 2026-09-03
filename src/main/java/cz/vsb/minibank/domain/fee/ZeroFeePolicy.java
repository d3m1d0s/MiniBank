package cz.vsb.minibank.domain.fee;

import cz.vsb.minibank.domain.value.Money;

/**
 * FeePolicy special case that always returns a zero fee. Nothing in the running application
 * selects it - see {@code BootstrapServices} - so it exists for tests that need the money paths
 * exercised with no fee term in the arithmetic, and for the one test that shows a settled
 * transfer keeping what it was charged when the policy is swapped underneath it.
 *
 * It never reads the amount, so it has nothing to validate: the currency guard belongs where the
 * amount is actually read, which is {@link SimpleFeePolicy}. What it must not do is hand back a
 * foreign zero. It used to, and that was the other half of one interface with two contradictory
 * contracts - it also meant a foreign amount failed later and further from its cause, at the
 * account balance comparison, instead of at the policy that was handed it.
 */
public final class ZeroFeePolicy implements FeePolicy {

    @Override
    public Money compute(Money amount) {
        return Money.czk(0.00);
    }
}
