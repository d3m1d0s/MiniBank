import { describe, it, expect } from 'vitest';
import { parseAmount, formatCzech, formatMoney } from './money';

/**
 * The amount a customer types is the one place in this application where the same string means
 * two different numbers depending on who is reading it, and where getting it wrong moved money.
 * `1,000` used to be accepted and paid 1.00 CZK behind a success panel.
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
        expect(formatMoney({ amount: '1500.00', currency: 'CZK' })).toBe('1500.00 CZK');
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
        expect(formatMoney(null)).toBe('—');
        expect(formatMoney(undefined)).toBe('—');
    });

    it('leaves the digits exactly as the server wrote them', () => {
        // Not run through formatCzech: these are amounts the bank has decided, not amounts a
        // person is typing, and regrouping them here would be a second formatting rule on one
        // value.
        expect(formatMoney({ amount: '999999.99', currency: 'CZK' })).toBe('999999.99 CZK');
    });
});
