package cz.vsb.minibank.domain.fee;

import cz.vsb.minibank.domain.value.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ZeroFeePolicyTest {

    private final ZeroFeePolicy policy = new ZeroFeePolicy();

    @Test
    void theFeeIsZeroWhateverTheAmount() {
        assertEquals(Money.czk(0.00), policy.compute(Money.czk(0.01)));
        assertEquals(Money.czk(0.00), policy.compute(Money.czk(123.45)));
        assertEquals(Money.czk(0.00), policy.compute(Money.czk(1_000_000)));
    }

    /**
     * This test used to assert the opposite: that a EUR amount produced a EUR fee, under the
     * comment "Fee must keep the same currency". That was one half of two implementations of one
     * interface contradicting each other, because {@link SimpleFeePolicy} refuses a foreign
     * amount rather than preserving it. The contract is now single-currency, and this pins the
     * side of it that changed.
     *
     * A foreign amount is refused where it is read, not here - this policy never looks at it - so
     * what is asserted is only that nothing foreign leaves.
     */
    @Test
    void aForeignAmountStillProducesACrownFee() {
        Money fee = policy.compute(Money.of("EUR", new BigDecimal("123.45")));

        assertEquals("CZK", fee.currency(), "the fee is in the bank's currency, not the amount's");
        assertEquals(0, fee.amount().compareTo(BigDecimal.ZERO), "Fee amount must be zero");
    }
}
