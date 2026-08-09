/**
 * Reading and writing CZK amounts the way a person types them.
 *
 * This bank is Czech: the decimal separator is a comma and digits are grouped with spaces, so
 * one and a half thousand crowns is `1 500,00`. But the browser is not necessarily Czech, and
 * the shape that breaks is the same string in both conventions. `1,000` is one thousand to
 * someone whose locale groups with a comma, and one crown to someone whose locale takes the
 * comma as a decimal point.
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
 */

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

/**
 * How a money value from the API is shown: the amount the server sent, then its currency.
 *
 * The one place either is printed. The server sends the two apart on purpose - an amount is a
 * number and its currency belongs to it - and a screen that interpolates them itself is a screen
 * that can forget the second half, which is exactly what both fraud desks did to the fee and to
 * the source balance while the amount above them carried a currency.
 *
 * The digits are left as the server wrote them rather than run through {@link formatCzech}.
 * These are amounts the bank has already decided, not amounts a person is typing, and grouping
 * them here would put a second formatting rule on the same value.
 *
 * An absent value prints as a dash. Null is meaningful on this wire: a payment that has not
 * settled has been charged nothing, which is not a charge of zero.
 */
export function formatMoney(money: { amount: string; currency: string } | null | undefined): string {
    return money ? `${money.amount} ${money.currency}` : '—';
}

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
 * Reads an amount, resolving `1,234` against the given locale.
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
