package cz.vsb.minibank.api.dto.common;

import cz.vsb.minibank.domain.value.Money;

/**
 * A money value on the wire: the amount as a decimal string, and the currency it is in.
 *
 * Two fields rather than one formatted string, and the currency attached to its own amount
 * rather than stated once per response. An amount and its unit are one value - that is what
 * {@link Money} is in the domain, and it is how every payment format states it, from ISO 20022's
 * Ccy attribute to a nested amount-and-currency object. A client handed "6500.00 CZK" in a single
 * field has to split a display string before it can compute with it, and this application already
 * refuses that shape on the way in: minibank-web's own amount parser rejects "1500 CZK".
 *
 * What it replaced on each side. The customer surfaces emitted Money.toString(), so the unit was
 * baked into the value. The fraud surfaces emitted a bare number with one currency field per
 * response covering three separate money fields - and both desks appended it to the amount and to
 * history rows and forgot the fee and the source balance, which is how a fee came to be printed
 * with no unit directly beneath an amount that had one.
 *
 * A string rather than a JSON number, because a number arrives as a double in most clients and
 * money is not one. The scale is whatever {@code Money} holds, which is always two.
 */
public record MoneyDto(String amount, String currency) {

    /**
     * The wire form of a money value, or null for one that is absent rather than zero.
     *
     * The distinction is real on this API: a transfer that has not settled has been charged
     * nothing, which is not the same fact as a charge of 0.00.
     */
    public static MoneyDto of(Money money) {
        return money == null
                ? null
                : new MoneyDto(money.amount().toPlainString(), money.currency());
    }
}
