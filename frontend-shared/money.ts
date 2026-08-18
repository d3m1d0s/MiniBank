import { EMPTY_VALUE } from './format';

/**
 * A money value as the API sends it: the amount as a decimal string, and the currency it is in.
 *
 * Two fields rather than one formatted string, so the amount stays something a client could
 * compute with and the currency belongs to that amount rather than to the response around it.
 * Print it with {@link formatMoney}, never by interpolating the fields at a call site, which is
 * how a fee came to be shown with no currency beside an amount that had one.
 */
export interface Money {
    amount: string;
    currency: string;
}

/**
 * The marks cs-CZ puts between digits, asked of the platform rather than written out here.
 *
 * The group mark is U+00A0 and the decimal mark is a comma, but neither is spelled as a literal:
 * an invisible byte in a source file is not a rule anybody can read, and the parser on the other
 * side of this convention already asks Intl the same question rather than keeping a list.
 */
const CZECH_MARKS = (() => {
    const parts = new Intl.NumberFormat('cs-CZ').formatToParts(1234.5);
    return {
        group: parts.find((p) => p.type === 'group')?.value ?? ' ',
        decimal: parts.find((p) => p.type === 'decimal')?.value ?? ',',
    };
})();

/** A decimal as the server writes one: optional sign, digits, and at most one dotted fraction. */
const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/;

/**
 * How a money value from the API is shown: the amount grouped the Czech way, then its currency.
 *
 * The one place either is printed. The server sends the two apart on purpose, an amount is a
 * number and its currency belongs to it, and a screen that interpolates them itself is a screen
 * that can forget the second half, which is exactly what both fraud desks did to the fee and to
 * the source balance while the amount above them carried a currency.
 *
 * THE GROUPING IS NEW AND THIS COMMENT USED TO ARGUE THE OPPOSITE. It said the digits were left
 * as the server wrote them because formatting them here would put a second rule on the same
 * value. That was true about the rule and wrong about the screen: `10001.00 CZK` was printed one
 * line above `13. 8. 2026 20:52`, so the page already carried two conventions and the amount was
 * the half in neither. The bank is Czech and the numbers follow cs-CZ, so this one does too.
 *
 * There is still only one rule. The digits the server sent are the digits printed: the string is
 * regrouped, never reparsed, so nothing is rounded, no scale is invented, and an amount larger
 * than a double can hold survives intact. An amount that is not a decimal at all is printed as it
 * arrived, because the server owns this field and a client that cannot read it should say what
 * came rather than say NaN.
 *
 * The currency stays the code the server chose. Czech convention decides the numerals; the prose
 * is English and the unit is neither, it is the server's own value, and the day this API carries
 * a second currency `Kč` would be a wrong word rather than a translated one.
 *
 * An absent value prints as the empty value. Null is meaningful on this wire: a payment that has
 * not settled has been charged nothing, which is not a charge of zero.
 */
export function formatMoney(money: Money | null | undefined): string {
    return money ? `${groupCzech(money.amount)} ${money.currency}` : EMPTY_VALUE;
}

function groupCzech(amount: string): string {
    const parsed = DECIMAL.exec(amount.trim());
    if (!parsed) {
        return amount;
    }

    const [, sign, whole, fraction] = parsed;
    // A break before every run of three that reaches the end of the whole part, and \B so that
    // an amount of exactly three digits keeps its leading position ungrouped.
    const grouped = whole.replace(/\B(?=(\d{3})+$)/g, CZECH_MARKS.group);

    return fraction
        ? `${sign}${grouped}${CZECH_MARKS.decimal}${fraction}`
        : `${sign}${grouped}`;
}
