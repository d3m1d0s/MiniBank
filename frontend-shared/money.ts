/**
 * A money value as the API sends it: the amount as a decimal string, and the currency it is in.
 *
 * Two fields rather than one formatted string, so the amount stays something a client could
 * compute with and the currency belongs to that amount rather than to the response around it.
 * Print it with {@link formatMoney} - never by interpolating the fields at a call site, which is
 * how a fee came to be shown with no currency beside an amount that had one.
 */
export interface Money {
    amount: string;
    currency: string;
}

/**
 * How a money value from the API is shown: the amount the server sent, then its currency.
 *
 * The one place either is printed. The server sends the two apart on purpose - an amount is a
 * number and its currency belongs to it - and a screen that interpolates them itself is a screen
 * that can forget the second half, which is exactly what both fraud desks did to the fee and to
 * the source balance while the amount above them carried a currency.
 *
 * The digits are left as the server wrote them rather than regrouped. These are amounts the bank
 * has already decided, not amounts a person is typing, and formatting them here would put a
 * second rule on the same value.
 *
 * An absent value prints as a dash. Null is meaningful on this wire: a payment that has not
 * settled has been charged nothing, which is not a charge of zero.
 */
export function formatMoney(money: Money | null | undefined): string {
    return money ? `${money.amount} ${money.currency}` : '—';
}
