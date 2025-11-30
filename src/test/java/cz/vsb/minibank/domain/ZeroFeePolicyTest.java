package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.value.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ZeroFeePolicyTest {

    @Test
    void computeReturnsZeroFeeWithSameCurrency() {
        ZeroFeePolicy policy = new ZeroFeePolicy();
        Money amount = Money.of("EUR", new BigDecimal("123.45"));

        Money fee = policy.compute(amount);

        assertEquals("EUR", fee.currency(), "Fee must keep the same currency");
        assertEquals(0, fee.amount().compareTo(BigDecimal.ZERO), "Fee amount must be zero");
    }
}
