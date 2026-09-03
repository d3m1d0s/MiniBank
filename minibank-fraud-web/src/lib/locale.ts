/**
 * The locale an ambiguous amount is read in.
 *
 * Only `1,234` needs it: the one string that is a valid number under both the Czech and the English
 * convention and means two different things under them. It is read here rather than inside the
 * parser because the shared modules are pure, so that their rules can be tested without a browser,
 * and this is the one thing in the reading that only a browser knows.
 *
 * A module of its own because two screens in this window now read a typed amount, the fraud desk's
 * two filter boxes and the payment form's one field, and money is the worst thing in this product
 * to hold two answers about: `1,000` is a thousand crowns under one reading and one crown under the
 * other. That is exactly the defect the shared parser was written to end, one level up.
 */
export function readerLocale(): string {
    return navigator.language || 'cs-CZ';
}
