package cz.vsb.minibank.domain.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class Money implements Comparable<Money> {
    private final BigDecimal amount;
    private final String currency; // ISO code, e.g. "CZK"

    public static Money of(String currency, BigDecimal amount) {
        return new Money(currency, amount);
    }
    public static Money of(String currency, double amount) {
        return new Money(currency, BigDecimal.valueOf(amount));
    }
    public static Money czk(double amount) { return of("CZK", amount); }
    public static Money czk(java.math.BigDecimal amount) {
        return of("CZK", amount);
    }

    private Money(String currency, BigDecimal amount) {
        this.currency = Objects.requireNonNull(currency);
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal amount() { return amount; }
    public String currency() { return currency; }

    public Money plus(Money other) { ensureSameCurrency(other); return of(currency, amount.add(other.amount)); }
    public Money minus(Money other) { ensureSameCurrency(other); return of(currency, amount.subtract(other.amount)); }
    public Money percent(double p) { return of(currency, amount.multiply(BigDecimal.valueOf(p/100.0))); }

    public boolean gte(Money other) { ensureSameCurrency(other); return amount.compareTo(other.amount) >= 0; }
    public boolean gt(Money other)  { ensureSameCurrency(other); return amount.compareTo(other.amount) > 0; }
    public boolean isNegative() { return amount.signum() < 0; }

    private void ensureSameCurrency(Money other) {
        if (!currency.equals(other.currency)) throw new IllegalArgumentException("Currency mismatch");
    }

    @Override public int compareTo(Money o) {
        ensureSameCurrency(o);
        return amount.compareTo(o.amount);
    }
    @Override public String toString() { return amount + " " + currency; }
}