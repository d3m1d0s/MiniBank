package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.value.Money;

/**
 * Strategy for computing transfer fees from the transfer amount.
 */
public interface FeePolicy {

    /**
     * Computes the fee for the given transfer amount.
     */
    Money compute(Money amount);
}
