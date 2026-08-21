import { EMPTY_VALUE } from './format';

/**
 * Money on both sides of the screen: the amounts the server has already decided, and the amounts a
 * person types.
 *
 * The reading half used to live in minibank-web/src/money.ts alone, and the analyst workstation
 * could not import it - one application may not reach into another's src - so it was written a
 * second time there under the name readAmount. Two parsers for one convention is how the two
 * applications come to accept different strings for the same payment, and this parser exists
 * because exactly that kind of divergence moved money: see the note on {@link parseAmount}.
 *
 * What is NOT here is which locale an ambiguous amount is read in. That answer comes from the
 * browser the person is sitting at, and this module is pure so that its rules can be tested
 * without one; each application asks its own navigator and passes the answer in.
 */

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
 * an invisible byte in a source file is not a rule anybody can read, and the parser below asks
 * Intl the same question rather than keeping a list of its own.
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

/**
 * What a payment cost, written for the line that sits under its amount.
 *
 * The sign is the whole reason this is a function rather than a call to {@link formatMoney} at
 * three call sites. Under an amount, an unsigned number reads as a second amount, and the two
 * questions a row answers here are what was sent and what it cost on top of that; the plus says
 * which of the two the smaller line is, and it says it in the one place all three tables read.
 *
 * Every history row has one now. `HistoryItem.fee` is never null - the wire prices a payment that
 * has not settled with the current tariff rather than sending nothing - so the null this still
 * accepts is a backstop against an older API and not a case the screens are designed around. It
 * was designed around one once: the field carried the charge alone, three of twelve demonstration
 * rows arrived empty, and on the analyst's desk it was most of the table.
 *
 * A fee of zero IS printed, as `+0,00 CZK`. The tariff is free below a threshold, so zero is a real
 * outcome the customer paid nothing for rather than a missing value, and a row that stays silent
 * about it is a row that looks like it forgot.
 *
 * THE CURRENCY STAYS ON THIS LINE, and it has now been argued twice, so the answer is written down
 * rather than left to be rediscovered. The case against it is that `+0,00 CZK` under `300,00 CZK`
 * has the shape of a second complete value, and a reader glancing at the pair can take the two for
 * the ends of a range before the sign registers. True of the glance and not of the reading: the
 * plus is what a range has no room for, and it is in front of every one of these lines by
 * construction.
 *
 * Against that glance stands the state this module was built to end. A fee printed bare under an
 * amount that carried a unit is the exact defect both fraud desks shipped, it is what the note on
 * {@link Money} points at, and there is a test named after it. Dropping the code here would put all
 * three tables back in that state and rest the unit on a neighbouring line staying where it is,
 * which is a layout promise this function cannot make and cannot check.
 *
 * The rejected form was `formatCzech(Number(fee.amount))`, which fails for a second reason that has
 * nothing to do with the unit: it reparses a decimal the server owns. {@link formatMoney} regroups
 * that string and never reparses it, precisely so that no scale is invented and nothing wider than
 * a double is rounded on its way to a screen, and a fee that took the other route would be the one
 * money value on the row printed by a different rule.
 */
export function formatFeeLine(fee: Money | null | undefined): string | null {
    return fee ? `+${formatMoney(fee)}` : null;
}

/**
 * Every kind of space, including the two the formatters emit.
 *
 * `\s` already covers U+00A0 and U+202F, which matters because a value pasted back from
 * Intl.NumberFormat is grouped with U+00A0 rather than with the space anyone can type. Spelling
 * those two out as literals would put invisible characters in the source to say what the class
 * already says.
 */
const WHITESPACE = /[\s]/g;

export type ParsedAmount =
    | { ok: true; value: number; czech: string }
    | { ok: false; reason: string };

/** Formats an amount the Czech way: `1 234,56`. */
export function formatCzech(value: number): string {
    return new Intl.NumberFormat('cs-CZ', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
    }).format(value);
}

/**
 * Whether this locale reads a comma as the decimal point.
 *
 * Asked of Intl rather than kept as a list, because the only question here is which of the two
 * readings the person in front of the screen expects, and the platform already knows.
 */
function commaIsDecimalIn(locale: string): boolean {
    const parts = new Intl.NumberFormat(locale).formatToParts(1234.5);
    return parts.find((p) => p.type === 'decimal')?.value === ',';
}

/**
 * Reads an amount the way a person types one, resolving `1,234` against the given locale.
 *
 * This bank is Czech: the decimal separator is a comma and digits are grouped with spaces, so one
 * and a half thousand crowns is `1 500,00`. But the browser is not necessarily Czech, and the shape
 * that breaks is the same string in both conventions. `1,000` is one thousand to someone whose
 * locale groups with a comma, and one crown to someone whose locale takes the comma as a decimal
 * point.
 *
 * The old parser did `Number(raw.replace(/\s+/g, '').replace(',', '.'))`, which is the second
 * reading unconditionally. Combined with the server stripping trailing zeros before counting
 * decimals, `1,000` was accepted and paid 1.00 CZK behind a success panel. Round numbers - the
 * ones people actually type - were the case it got wrong.
 *
 * So the ambiguous shape is resolved against the reader's own locale, and then the result is
 * written back into the field in Czech. The customer never has to guess which reading they got:
 * they see it.
 *
 * Spaces are treated as noise and removed before anything else, so `1 23,00` reads as 123 rather
 * than being refused for grouping that does not run in threes. That is deliberate and it is the
 * echo that makes it safe: whatever was understood appears in the field, so a slip shows itself.
 * Grouping IS checked when it is written with dots or commas, because there a wrong grouping
 * changes the reading by a factor of a thousand rather than announcing itself.
 *
 * @param locale the reader's locale; only consulted for the one genuinely ambiguous shape
 */
export function parseAmount(raw: string, locale: string): ParsedAmount {
    const compact = raw.replace(WHITESPACE, '');

    if (compact === '') {
        return { ok: false, reason: 'Enter the amount to send.' };
    }
    if (!/^[0-9.,]+$/.test(compact)) {
        return {
            ok: false,
            reason: 'An amount is digits, spaces between thousands and one decimal comma, like 1 500,00.',
        };
    }

    const commas = (compact.match(/,/g) ?? []).length;
    const dots = (compact.match(/\./g) ?? []).length;

    let decimalSep: string | null;
    if (commas > 0 && dots > 0) {
        // Both kinds present, so one groups and one divides, and the rightmost divides.
        decimalSep = compact.lastIndexOf(',') > compact.lastIndexOf('.') ? ',' : '.';
    } else if (commas + dots === 0) {
        decimalSep = null;
    } else if (commas > 1 || dots > 1) {
        // A separator that repeats cannot be the decimal point.
        decimalSep = null;
    } else {
        const sep = commas === 1 ? ',' : '.';
        const digitsAfter = compact.length - compact.indexOf(sep) - 1;
        const digitsBefore = compact.indexOf(sep);

        // Exactly three digits after a single separator, with something in front of it, is the
        // one shape that is a valid number under either reading. Nothing about the string can
        // settle it, so the reader's locale does.
        if (digitsAfter === 3 && digitsBefore > 0) {
            decimalSep = commaIsDecimalIn(locale) === (sep === ',') ? sep : null;
        } else {
            decimalSep = sep;
        }
    }

    const cut = decimalSep === null ? compact.length : compact.lastIndexOf(decimalSep);
    const grouped = compact.slice(0, cut);
    const fraction = decimalSep === null ? '' : compact.slice(cut + 1);
    const digits = grouped.replace(/[.,]/g, '');

    // Whatever is left of the decimal point is grouping, and grouping runs in threes. Checked so
    // that a mistyped `1.2345` is refused rather than silently read as twelve thousand.
    if (/[.,]/.test(grouped)) {
        const groups = grouped.split(/[.,]/);
        const wellGrouped =
            groups[0].length >= 1 &&
            groups[0].length <= 3 &&
            groups.slice(1).every((g) => g.length === 3);
        if (!wellGrouped) {
            return { ok: false, reason: 'Group the digits in threes, like 1 234 567,00.' };
        }
    }

    if (fraction.length > 2) {
        return {
            ok: false,
            reason: 'An amount goes no finer than a heller, so at most two digits after the comma.',
        };
    }
    if (digits === '' && fraction === '') {
        return { ok: false, reason: 'Enter the amount to send.' };
    }

    const value = Number(`${digits || '0'}.${fraction || '0'}`);
    if (!Number.isFinite(value) || value <= 0) {
        return { ok: false, reason: 'Enter an amount greater than zero.' };
    }

    return { ok: true, value, czech: formatCzech(value) };
}
