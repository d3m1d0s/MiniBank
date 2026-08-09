package cz.vsb.minibank.domain.value;

import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
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
     * ISO 4217 alphabetic code such as "CZK", in the form the standard writes it.
     *
     * A String rather than a one-constant enum, and that is a decision. An enum would make a
     * foreign currency unrepresentable, which sounds right for a bank that keeps one - and would
     * take with it the guard in {@code Transfer}'s constructor, the guard in
     * {@code SimpleFeePolicy}, and the tests that pin both. The single-currency rule would stop
     * being something the code states and starts refusing, and become something the type system
     * quietly makes impossible to discuss.
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

    /** ISO 4217 alphabetic codes are three letters, and every reference implementation writes
     *  them upper case: {@link java.util.Currency#getInstance(String)}, Joda-Money and JSR-354
     *  all require that form and none of them folds case. */
    private static final int CURRENCY_CODE_LENGTH = 3;

    private Money(String currency, BigDecimal amount) {
        this.currency = requireCurrencyCode(currency);
        this.amount = Objects.requireNonNull(amount, "amount").setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The currency, or a refusal that a value object was asked to hold something that is not a
     * currency code.
     *
     * The field used to take any string at all, so {@code ""} and {@code "XYZZY"} were both money
     * this application could hold, and {@code "czk"} was a currency that no arithmetic would
     * accept alongside {@code "CZK"} - {@code equals} answering false and {@code plus} raising a
     * bare "Currency mismatch". That is the same trap {@link IBAN#equals} says it exists to
     * prevent, one class along in this package.
     *
     * Case is checked rather than folded, and the difference matters here. {@code IBAN} normalizes
     * because a person types an IBAN; nothing types a currency in this application, so a stored
     * code that is not already in its canonical form was written by hand and is refused like any
     * other stored value this application would never write. Folding it would also be unsafe
     * rather than merely lenient: {@code JsonTransferRepository} compares the raw stored string
     * when summing the day, so a normalized {@code "czk"} would load as crowns and spend against
     * no daily ceiling.
     *
     * A foreign but well-formed code stays constructible on purpose. Both loaders rebuild a stored
     * amount in the currency its row names precisely so that {@code Transfer}'s constructor can
     * refuse it and say which currency it was.
     *
     * @throws DataIntegrityException when the code is not three upper case ASCII letters. That
     *         type rather than a validation error, and for the reason {@code Transfer}'s own
     *         currency guard gives: no caller can reach this with anything but a literal, so the
     *         only string that ever fails it came out of a store. The refusal names the value but
     *         not the row it came from - a value object has no idea which row it is being built
     *         for, and threading one in to improve a message would be worse than the message
     *         being one grep short.
     */
    private static String requireCurrencyCode(String currency) {
        Objects.requireNonNull(currency, "currency");
        if (currency.length() != CURRENCY_CODE_LENGTH) {
            throw new DataIntegrityException("Not a currency code: " + currency);
        }
        for (int i = 0; i < CURRENCY_CODE_LENGTH; i++) {
            char c = currency.charAt(i);
            if (c < 'A' || c > 'Z') {
                throw new DataIntegrityException("Not a currency code: " + currency);
            }
        }
        return currency;
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
     * Compared exactly, which is safe because the constructor refuses anything that is not
     * already a canonical code: two values in the same currency cannot be spelled differently,
     * so this can no longer answer false for a value that is really the same money.
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
