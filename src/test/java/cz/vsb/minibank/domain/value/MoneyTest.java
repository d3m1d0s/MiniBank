package cz.vsb.minibank.domain.value;

import cz.vsb.minibank.domain.exceptions.InvalidAmountException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Money value object: equality, scale normalization,
 * rounding and rejection of non-finite amounts.
 */
class MoneyTest {

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Test
    void equalAmountsInSameCurrencyAreEqual() {
        assertEquals(Money.czk(100), Money.czk(100));
        assertEquals(Money.czk(100).hashCode(), Money.czk(100).hashCode());
    }

    @Test
    void equalityIgnoresTheScaleOfTheInputAmount() {
        Money fromDouble = Money.czk(100);
        Money fromScaledDecimal = Money.of("CZK", new BigDecimal("100.000"));

        assertEquals(fromDouble, fromScaledDecimal);
        assertEquals(fromDouble.hashCode(), fromScaledDecimal.hashCode());
        assertEquals(0, fromDouble.compareTo(fromScaledDecimal));
    }

    @Test
    void equalValuesCollapseInAHashSet() {
        Set<Money> set = new HashSet<>(List.of(
                Money.czk(5),
                Money.czk(5),
                Money.of("CZK", new BigDecimal("5.00"))
        ));

        assertEquals(1, set.size());
    }

    @Test
    void differentAmountsAndCurrenciesAreNotEqual() {
        assertNotEquals(Money.czk(100), Money.czk(101));
        assertNotEquals(Money.czk(100), Money.of("EUR", 100));
        assertNotEquals(Money.czk(100), null);
        assertNotEquals(Money.czk(100), "100.00 CZK");
    }

    // -------------------------------------------------------------------------
    // Scale and rounding
    // -------------------------------------------------------------------------

    @Test
    void amountIsAlwaysNormalizedToTwoDecimalPlaces() {
        assertEquals(2, Money.czk(5).amount().scale());
        assertEquals(2, Money.of("CZK", new BigDecimal("5.123456")).amount().scale());
        assertEquals("5.00 CZK", Money.czk(5).toString());
    }

    @Test
    void roundingIsHalfUp() {
        assertEquals(new BigDecimal("0.01"), Money.of("CZK", new BigDecimal("0.005")).amount());
        assertEquals(new BigDecimal("2.68"), Money.of("CZK", new BigDecimal("2.675")).amount());
        assertEquals(new BigDecimal("2.67"), Money.of("CZK", new BigDecimal("2.674")).amount());
    }

    // -------------------------------------------------------------------------
    // Sign predicates
    // -------------------------------------------------------------------------

    @Test
    void signPredicatesReportTheAmountSign() {
        assertTrue(Money.czk(0.01).isPositive());
        assertFalse(Money.czk(0.01).isZero());
        assertFalse(Money.czk(0.01).isNegative());

        assertFalse(Money.czk(0).isPositive());
        assertTrue(Money.czk(0).isZero());
        assertFalse(Money.czk(0).isNegative());

        assertFalse(Money.czk(-1).isPositive());
        assertFalse(Money.czk(-1).isZero());
        assertTrue(Money.czk(-1).isNegative());
    }

    @Test
    void anAmountBelowHalfAUnitRoundsToZeroAndIsNotPositive() {
        assertTrue(Money.czk(0.004).isZero());
        assertFalse(Money.czk(0.004).isPositive());
    }

    // -------------------------------------------------------------------------
    // Non-finite amounts
    // -------------------------------------------------------------------------

    @Test
    void nonFiniteAmountsAreRejectedAsDomainErrors() {
        assertThrows(InvalidAmountException.class, () -> Money.czk(Double.NaN));
        assertThrows(InvalidAmountException.class, () -> Money.czk(Double.POSITIVE_INFINITY));
        assertThrows(InvalidAmountException.class, () -> Money.czk(Double.NEGATIVE_INFINITY));
        assertThrows(InvalidAmountException.class, () -> Money.of("CZK", Double.NaN));
    }

    @Test
    void aNullAmountIsRejected() {
        assertThrows(NullPointerException.class, () -> Money.of("CZK", (BigDecimal) null));
    }

    // -------------------------------------------------------------------------
    // Arithmetic keeps the currency guard
    // -------------------------------------------------------------------------

    @Test
    void arithmeticRequiresMatchingCurrencies() {
        Money czk = Money.czk(100);
        Money eur = Money.of("EUR", 100);

        assertThrows(IllegalArgumentException.class, () -> czk.plus(eur));
        assertThrows(IllegalArgumentException.class, () -> czk.minus(eur));
        assertThrows(IllegalArgumentException.class, () -> czk.gte(eur));
        assertThrows(IllegalArgumentException.class, () -> czk.compareTo(eur));
    }

    @Test
    void arithmeticProducesNormalizedResults() {
        assertEquals(Money.czk(150.50), Money.czk(100.25).plus(Money.czk(50.25)));
        assertEquals(Money.czk(50), Money.czk(100).minus(Money.czk(50)));
        assertEquals(Money.czk(10), Money.czk(1000).percent(1.0));
    }
}
