package cz.vsb.minibank.domain.value;

import cz.vsb.minibank.domain.exceptions.InvalidAmountException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable monetary value with currency and two decimal places.
 */
public final class Money implements Comparable<Money> {

    /**
     * Monetary amount rounded to two decimal places.
     */
    private final BigDecimal amount;

    /**
     * ISO currency code such as "CZK".
     */
    private final String currency;

    /**
     * Creates a Money instance with the given currency and amount.
     */
    public static Money of(String currency, BigDecimal amount) {
        return new Money(currency, amount);
    }

    /**
     * Creates a Money instance with the given currency and double amount.
     *
     * @throws InvalidAmountException when the amount is NaN or infinite
     */
    public static Money of(String currency, double amount) {
        if (!Double.isFinite(amount)) {
            throw new InvalidAmountException("Amount must be a finite number: " + amount);
        }
        return new Money(currency, BigDecimal.valueOf(amount));
    }

    /**
     * Creates a Money instance in CZK from a double amount.
     */
    public static Money czk(double amount) {
        return of("CZK", amount);
    }

    /**
     * Creates a Money instance in CZK from a BigDecimal amount.
     */
    public static Money czk(java.math.BigDecimal amount) {
        return of("CZK", amount);
    }

    /**
     * Creates a CZK amount that a caller asked to move.
     * Stricter than {@link #czk(double)}: the value must be finite, strictly positive and
     * already expressible in whole hellers. Rounding a requested amount would move money
     * the caller did not ask for, so 0.005 is rejected rather than charged as 0.01.
     *
     * @throws InvalidAmountException when the value cannot be used as a payment amount
     */
    public static Money czkPayment(double amount) {
        if (!Double.isFinite(amount)) {
            throw new InvalidAmountException("Amount must be a finite number: " + amount);
        }

        // BigDecimal.valueOf gives the shortest decimal form of the double, so the scale
        // after stripping zeros is the precision the caller actually typed.
        BigDecimal requested = BigDecimal.valueOf(amount);
        if (requested.stripTrailingZeros().scale() > 2) {
            throw new InvalidAmountException(
                    "Amount must not be more precise than 0.01: " + requested.toPlainString());
        }

        Money money = czk(requested);
        if (!money.isPositive()) {
            throw new InvalidAmountException("Amount must be greater than zero: " + money);
        }
        return money;
    }

    private Money(String currency, BigDecimal amount) {
        this.currency = Objects.requireNonNull(currency);
        this.amount = Objects.requireNonNull(amount, "amount").setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns the numeric amount.
     */
    public BigDecimal amount() { return amount; }

    /**
     * Returns the ISO currency code.
     */
    public String currency() { return currency; }

    /**
     * Returns a new Money equal to this plus the other amount.
     *
     * @throws IllegalArgumentException when currencies differ
     */
    public Money plus(Money other) {
        ensureSameCurrency(other);
        return of(currency, amount.add(other.amount));
    }

    /**
     * Returns a new Money equal to this minus the other amount.
     *
     * @throws IllegalArgumentException when currencies differ
     */
    public Money minus(Money other) {
        ensureSameCurrency(other);
        return of(currency, amount.subtract(other.amount));
    }

    /**
     * Returns a percentage of this amount as a new Money.
     *
     * @param p percentage value, for example 1.0 for one percent
     */
    public Money percent(double p) {
        return of(currency, amount.multiply(BigDecimal.valueOf(p / 100.0)));
    }

    /**
     * Returns true when this amount is greater than or equal to the other amount.
     *
     * @throws IllegalArgumentException when currencies differ
     */
    public boolean gte(Money other) {
        ensureSameCurrency(other);
        return amount.compareTo(other.amount) >= 0;
    }

    /**
     * Returns true when this amount is strictly greater than the other amount.
     *
     * @throws IllegalArgumentException when currencies differ
     */
    public boolean gt(Money other)  {
        ensureSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    /**
     * Returns true when the amount is negative.
     */
    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /**
     * Returns true when the amount is strictly greater than zero.
     */
    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /**
     * Returns true when the amount is exactly zero.
     */
    public boolean isZero() {
        return amount.signum() == 0;
    }

    /**
     * Ensures that both Money values use the same currency.
     *
     * @throws IllegalArgumentException when currencies differ
     */
    private void ensureSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch");
        }
    }

    @Override
    public int compareTo(Money o) {
        ensureSameCurrency(o);
        return amount.compareTo(o.amount);
    }

    /**
     * Two Money values are equal when both currency and amount match.
     * The constructor normalizes every amount to scale 2, so comparing with
     * BigDecimal.equals is consistent with compareTo.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money other)) return false;
        return currency.equals(other.currency) && amount.equals(other.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(currency, amount);
    }

    @Override
    public String toString() {
        return amount + " " + currency;
    }
}
