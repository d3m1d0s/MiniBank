import { describe, it, expect } from 'vitest';
import { parseAmount, formatCzech, formatFeeLine, formatMoney } from '@shared/money';
import { EMPTY_VALUE } from '@shared/format';

/**
 * The amount a customer types is the one place in these applications where the same string means
 * two different numbers depending on who is reading it, and where getting it wrong moved money.
 * `1,000` used to be accepted and paid 1.00 CZK behind a success panel.
 *
 * The subject is the shared module rather than this application's own. The parser was written
 * twice, once per application, and it is one function now; these assertions moved with it and are
 * the only ones the second copy ever had. They stay in this project because it is the one that
 * runs a test command - the workstation has none - and they sit beside the other five files that
 * test the shared layer.
 *
 * Locales are passed explicitly everywhere below rather than read from the environment, so these
 * assertions say what they mean on any machine.
 */

/**
 * cs-CZ groups with U+00A0, which is not the space anyone can type.
 *
 * Written as an escape rather than as the character, so that the assertions below are
 * readable: an invisible byte in a string literal is exactly what this parser exists to
 * handle, and it should not also be what the test is written in.
 */
const NBSP = '\u00A0';

const CZ = 'cs-CZ';
const EN = 'en-US';
const DE = 'de-DE'; // dot groups, comma divides - the Czech reading
const FR = 'fr-FR'; // narrow no-break space groups, comma divides

/** The parsed value, or null when the amount was refused. */
function value(raw: string, locale: string): number | null {
    const parsed = parseAmount(raw, locale);
    return parsed.ok ? parsed.value : null;
}

/** The message a refusal gives, or null when it was accepted. */
function refusal(raw: string, locale: string): string | null {
    const parsed = parseAmount(raw, locale);
    return parsed.ok ? null : parsed.reason;
}

describe('the defect this parser exists for', () => {
    // Every one of these was ACCEPTED by the old parser and paid a thousandth of the amount.
    // The server could not catch them: it strips trailing zeros before counting decimals, so
    // 1.000 arrives as scale 0 and passes the two-decimal rule.
    it.each([
        ['1,000', 1000],
        ['1,500', 1500],
        ['2,500', 2500],
        ['10,000', 10000],
        ['250,000', 250000],
    ])('reads %s as %i for a reader whose locale groups with a comma', (raw, expected) => {
        expect(value(raw, EN)).toBe(expected);
    });

    it('refuses the same string for a Czech reader, because there it really is three decimals', () => {
        expect(value('1,000', CZ)).toBeNull();
        expect(refusal('1,000', CZ)).toContain('two digits after the comma');
    });

    it('is consistent about it: a leading comma is refused for the same reason', () => {
        // ,500 is 0.500 under the Czech reading. Accepting this while refusing 1,500 would be
        // two different rules for one convention.
        expect(value(',500', CZ)).toBeNull();
        expect(value(',50', CZ)).toBe(0.5);
    });
});

describe('Czech input', () => {
    it.each([
        ['1500', 1500],
        [`1${NBSP}500,00`, 1500],
        ['1 500,00', 1500],
        ['1500,5', 1500.5],
        ['1500,50', 1500.5],
        ['0,01', 0.01],
        [`1${NBSP}234${NBSP}567,89`, 1234567.89],
        ['1.234.567,89', 1234567.89],
        ['999,99', 999.99],
    ])('reads %s', (raw, expected) => {
        expect(value(raw, CZ)).toBe(expected);
    });

    it('reads the same whoever is looking, once it is unambiguous', () => {
        for (const locale of [CZ, EN, DE, FR]) {
            expect(value('1 500,00', locale)).toBe(1500);
            expect(value('1500', locale)).toBe(1500);
            expect(value('0,01', locale)).toBe(0.01);
        }
    });
});

describe('input in the other convention', () => {
    it.each([
        ['1234.56', 1234.56],
        ['1,234,567.89', 1234567.89],
        ['1,234.5', 1234.5],
        ['999.99', 999.99],
    ])('reads %s for an en-US reader', (raw, expected) => {
        expect(value(raw, EN)).toBe(expected);
    });

    it('takes the rightmost separator as the decimal point when both are present', () => {
        // Nothing about the locale is needed here: only one reading leaves valid grouping.
        expect(value('1.234,56', EN)).toBe(1234.56);
        expect(value('1,234.56', CZ)).toBe(1234.56);
    });
});

describe('the ambiguous shape, resolved against the reader', () => {
    // A single separator with exactly three digits behind it and something in front. This is
    // the only string the parser cannot settle on its own.
    it('follows a locale that divides with a comma', () => {
        for (const locale of [CZ, DE, FR]) {
            expect(value('1,234', locale)).toBeNull(); // 1.234 - three decimals
            expect(value('1.234', locale)).toBe(1234); // dots group
        }
    });

    it('follows a locale that divides with a dot', () => {
        expect(value('1,234', EN)).toBe(1234); // commas group
        expect(value('1.234', EN)).toBeNull(); // 1.234 - three decimals
    });

    it('is not ambiguous when the digit count is anything but three', () => {
        for (const locale of [CZ, EN]) {
            expect(value('1,2', locale)).toBe(1.2);
            expect(value('1,23', locale)).toBe(1.23);
            expect(value('1,2345', locale)).toBeNull();
        }
    });

    it('is not ambiguous when a separator repeats, because then it can only be grouping', () => {
        expect(value('1,234,567', EN)).toBe(1234567);
        expect(value('1.234.567', CZ)).toBe(1234567);
    });
});

describe('what is refused, and what it says', () => {
    it.each([
        ['', 'Enter the amount to send.'],
        ['   ', 'Enter the amount to send.'],
    ])('refuses %s as empty', (raw, message) => {
        expect(refusal(raw, CZ)).toBe(message);
    });

    it.each(['abc', '1500 CZK', '12a', '1,5,5', '-5', '+5', '1e3'])(
        'refuses %s as not an amount at all',
        (raw) => {
            expect(value(raw, CZ)).toBeNull();
        },
    );

    it.each(['0', '0,00', '0.00', '0,0'])('refuses %s because it moves no money', (raw) => {
        expect(refusal(raw, CZ)).toContain('greater than zero');
    });

    it.each(['1,2345', '0,001', '1234,5678'])(
        'refuses %s for going finer than a heller',
        (raw) => {
            expect(refusal(raw, CZ)).toContain('two digits after the comma');
        },
    );

    it.each(['1.2345,00', '12.34.567,00', '1234.5,00'])(
        'refuses %s because the grouping does not run in threes',
        (raw) => {
            expect(refusal(raw, CZ)).toContain('threes');
        },
    );

    it('treats spaces as noise rather than as grouping to be checked', () => {
        // Deliberate, and safe only because the field writes the reading back: 123,00 appears
        // where the customer typed, so a slip shows itself instead of being refused.
        expect(value('1 23,00', CZ)).toBe(123);
        expect(value('15 00', CZ)).toBe(1500);
    });
});

describe('what the field writes back', () => {
    it('formats the Czech way, grouping with a non-breaking space', () => {
        expect(formatCzech(1000)).toBe(`1${NBSP}000,00`);
        expect(formatCzech(1234567.89)).toBe(`1${NBSP}234${NBSP}567,89`);
    });

    it('always shows both heller digits, so an amount never looks rounder than it is', () => {
        expect(formatCzech(1)).toBe('1,00');
        expect(formatCzech(0.5)).toBe('0,50');
        expect(formatCzech(0.01)).toBe('0,01');
    });

    it.each([1, 0.01, 0.5, 999.99, 1000, 1234.56, 1234567.89])(
        'reads back what it wrote for %d, whoever is reading',
        (amount) => {
            const written = formatCzech(amount);
            for (const locale of [CZ, EN, DE, FR]) {
                const back = parseAmount(written, locale);
                expect(back.ok).toBe(true);
                if (back.ok) expect(back.value).toBe(amount);
            }
        },
    );

    it('is idempotent, so normalizing an already normal amount changes nothing', () => {
        const once = parseAmount('1,000', EN);
        expect(once.ok).toBe(true);
        if (!once.ok) return;

        const twice = parseAmount(once.czech, EN);
        expect(twice.ok).toBe(true);
        if (twice.ok) expect(twice.czech).toBe(once.czech);
    });
});

describe('the edges of what a payment can be', () => {
    it('accepts one heller, the smallest payment the server will take', () => {
        expect(value('0,01', CZ)).toBe(0.01);
    });

    it('keeps two decimals exact rather than drifting, which is what the server refuses on', () => {
        // 0.1 + 0.2 territory. The parser builds the number from its digits rather than by
        // arithmetic, so the value carries exactly the two decimals that were typed.
        expect(value('0,07', CZ)).toBe(0.07);
        expect(value('1234567,89', CZ)).toBe(1234567.89);
        expect(value('8,10', CZ)).toBe(8.1);
    });

    it('handles an amount larger than any real balance without losing digits', () => {
        expect(value('999 999 999,99', CZ)).toBe(999999999.99);
    });
});

describe('showing an amount the server has already decided', () => {
    it('prints the amount and its currency together', () => {
        expect(formatMoney({ amount: '1500.00', currency: 'CZK' })).toBe(`1${NBSP}500,00 CZK`);
    });

    it('never prints an amount without its unit, which is the defect it exists to prevent', () => {
        // Both fraud desks used to append a separate currency field to the amount and forget it
        // on the fee and on the source balance, so a fee appeared bare directly beneath an
        // amount that carried one. Going through one function is what makes that unwritable.
        const shown = formatMoney({ amount: '25.00', currency: 'CZK' });
        expect(shown).toMatch(/\s CZK$|CZK$/);
        expect(shown).not.toBe('25.00');
    });

    it('shows a dash for an absent value rather than an empty gap', () => {
        // Null is meaningful here: a payment that has not settled has been charged nothing,
        // which is a different fact from a charge of zero.
        expect(formatMoney(null)).toBe(EMPTY_VALUE);
        expect(formatMoney(undefined)).toBe(EMPTY_VALUE);
    });

    it('groups the digits the Czech way, which is what the dates beside them already do', () => {
        // 10001.00 CZK used to be printed one line above 13. 8. 2026 20:52, so the page carried
        // two conventions and the amount was the half in neither.
        expect(formatMoney({ amount: '10001.00', currency: 'CZK' })).toBe(`10${NBSP}001,00 CZK`);
        expect(formatMoney({ amount: '999999.99', currency: 'CZK' })).toBe(
            `999${NBSP}999,99 CZK`,
        );
        expect(formatMoney({ amount: '50.00', currency: 'CZK' })).toBe('50,00 CZK');
    });

    it('regroups rather than reparses, so the digits are still the ones the server sent', () => {
        // Past what a double can hold. Nothing here goes through Number(), so nothing rounds.
        expect(formatMoney({ amount: '12345678901234567.89', currency: 'CZK' })).toBe(
            `12${NBSP}345${NBSP}678${NBSP}901${NBSP}234${NBSP}567,89 CZK`,
        );
        // The scale is the server's. Two decimals are not invented for a whole number, and a
        // third is not thrown away.
        expect(formatMoney({ amount: '1500', currency: 'CZK' })).toBe(`1${NBSP}500 CZK`);
        expect(formatMoney({ amount: '1.005', currency: 'CZK' })).toBe('1,005 CZK');
    });

    it('keeps the currency as the code the server chose', () => {
        // Czech convention decides the numerals. The unit is the server's own value, and Kč
        // would be a wrong word rather than a translated one the day a second currency appears.
        expect(formatMoney({ amount: '10.00', currency: 'EUR' })).toBe('10,00 EUR');
    });

    it('prints an amount it cannot read as it arrived, rather than as NaN', () => {
        expect(formatMoney({ amount: 'unknown', currency: 'CZK' })).toBe('unknown CZK');
    });

    it('reads back through the input parser, so the echo and the receipt agree', () => {
        // The amount field writes 1 500,00 into the box; the confirmation beneath it prints
        // 1 500,00 CZK. One convention, so the customer is not asked to match two.
        const shown = formatMoney({ amount: '1500.00', currency: 'CZK' });
        expect(value(shown.replace(' CZK', ''), CZ)).toBe(1500);
    });
});

/**
 * The fee as it stands under the amount it was added to, on all three history tables.
 *
 * The rule that decides whether the line exists at all lives in this one function rather than in
 * each table, because it is the same rule three times and the answer is not obvious from the row:
 * a fee of nothing and a fee of zero read alike in a cell and mean opposite things. The screens do
 * nothing but ask whether they were given a string.
 */
describe('the fee that stands under an amount', () => {
    it('marks it as added rather than as a second amount beside the first', () => {
        // The sign carries the whole of the wording. Nothing on the row says the word "fee", the
        // line is right aligned under the amount, and the plus is what makes it an addition to
        // that number instead of an unlabelled money value in the same cell.
        expect(formatFeeLine({ amount: '15.00', currency: 'CZK' })).toBe('+15,00 CZK');
        expect(formatFeeLine({ amount: '125.01', currency: 'CZK' })).toBe('+125,01 CZK');
    });

    it('prints a charge of nothing, because that is an answer and not an absence', () => {
        // "This one cost you nothing" is the thing the line exists to say, and it is the reading
        // a customer is least able to work out for themselves. A blank here would ask them to
        // know the tariff before they could tell a free payment from one whose fee went missing.
        expect(formatFeeLine({ amount: '0.00', currency: 'CZK' })).toBe('+0,00 CZK');
    });

    it('prints no line at all where nothing has been charged yet', () => {
        // The same distinction formatMoney states for null, and the reason the wire sends null
        // rather than zero: a payment that has not settled has been charged nothing. Null and not
        // a dash, because the cell above it is already occupied by the amount and a dash under it
        // would be read as a charge the screen could not name.
        expect(formatFeeLine(null)).toBeNull();
        expect(formatFeeLine(undefined)).toBeNull();
    });

    it('groups its digits the same way the amount above it does', () => {
        // Two lines of one sum, so a fee whose thousands are grouped differently from the amount
        // is two numbers in one cell rather than one number and its addition.
        expect(formatFeeLine({ amount: '1500.00', currency: 'CZK' })).toBe(`+1${NBSP}500,00 CZK`);
    });

    it('carries the currency, which is the defect the whole module exists to prevent', () => {
        // This is the exact value that used to be printed bare under an amount that had a unit,
        // on both fraud desks. It goes through formatMoney, so there is no call site left that
        // could forget it, and the code stays the server's own the day a second currency appears.
        expect(formatFeeLine({ amount: '25.00', currency: 'CZK' })).toContain('CZK');
        expect(formatFeeLine({ amount: '10.00', currency: 'EUR' })).toBe('+10,00 EUR');
    });

    it('says the same digits the amount formatter would, with one character in front', () => {
        // Derived rather than spelled out a second time: the point is that there is one rule for
        // printing money and this line is that rule with a sign, not a second convention.
        for (const amount of ['15.00', '0.00', '125.01', '1500.00', '10001.00']) {
            const fee = { amount, currency: 'CZK' };
            expect(formatFeeLine(fee)).toBe(`+${formatMoney(fee)}`);
        }
    });
});
