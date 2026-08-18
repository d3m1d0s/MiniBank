import { describe, it, expect } from 'vitest';
import {
    EMPTY_VALUE,
    formatAlertId,
    formatDate,
    formatDateTime,
    formatIban,
    formatTransferId,
    NOT_RECORDED,
} from '@shared/format';

/**
 * These are the formats that used to be decided at the call site, so the defects they exist for
 * are all disagreements: the same instant printed two ways on two computers, the same alert
 * printed two ways eight lines apart on one screen.
 *
 * Nothing below reads the environment. The formatter pins both the locale and the bank's zone,
 * which is the whole point of it, so these assertions say the same thing on any machine.
 */

/** cs-CZ groups and spaces with U+00A0, which is not the space anyone can type. */
const NBSP = '\u00A0';

/** 13 August 2026, 20:52:20 in Ostrava, as an Instant leaves the server: UTC, with a Z. */
const AUGUST_EVENING = '2026-08-13T18:52:20Z';

describe('an instant, printed the same wherever it is opened', () => {
    it('prints the Czech way and stops at the minute', () => {
        expect(formatDateTime(AUGUST_EVENING)).toBe('13. 8. 2026 20:52');
    });

    it('prints the time in the bank zone, not in the reader machine zone', () => {
        // The same instant is 14:52 in New York and 02:52 the next day in Tokyo. A payment made
        // at 20:52 in Ostrava has one time, and the demonstration must not disagree with itself
        // depending on which desk it is opened at.
        expect(formatDateTime(AUGUST_EVENING)).toContain('20:52');
    });

    it('follows the bank zone across the summer time boundary', () => {
        // Prague is UTC+2 in August and UTC+1 in January. A fixed offset would put this one an
        // hour out; naming the zone is what keeps both right.
        expect(formatDateTime('2026-01-05T07:03:00Z')).toBe('5. 1. 2026 08:03');
        expect(formatDateTime('2026-08-13T18:52:20Z')).toBe('13. 8. 2026 20:52');
    });

    it('drops the seconds, which decided nothing and cost four characters a column', () => {
        expect(formatDateTime(AUGUST_EVENING)).not.toContain(':20');
    });

    it('gives a date on its own where the time of day decides nothing', () => {
        expect(formatDate(AUGUST_EVENING)).toBe('13. 8. 2026');
    });
});

describe('a timestamp that is absent or unreadable', () => {
    it.each([null, undefined, ''])('draws the empty value for %s', (missing) => {
        // Drawn rather than left out: a gap in a table reads as a table that failed to load.
        expect(formatDateTime(missing)).toBe(EMPTY_VALUE);
        expect(formatDate(missing)).toBe(EMPTY_VALUE);
    });

    it('prints a string it cannot read as itself, rather than as Invalid Date', () => {
        // The server owns this field. A client that cannot read it should say what arrived.
        expect(formatDateTime('yesterday')).toBe('yesterday');
        expect(formatDate('not a date')).toBe('not a date');
    });
});

describe('the two ways an absent value is written', () => {
    it('draws a mark in a column and a word in prose', () => {
        // A dash in a definition list reads as a redaction, as though something is known and
        // withheld. Both are spelled once here so neither drifts into n/a on the next screen.
        expect(EMPTY_VALUE).not.toMatch(/[a-z]/i);
        expect(NOT_RECORDED).toMatch(/^[a-z ]+$/);
    });
});

describe('naming the same object the same way', () => {
    it('writes an alert the way the queue already writes it', () => {
        // The queue printed ALERT-2 and the details panel directly beneath it printed 2.
        expect(formatAlertId(2)).toBe('ALERT-2');
        expect(formatTransferId(9)).toBe('TR-9');
    });

    it('matches the server, which builds the same two strings for the queue', () => {
        // FraudController formats "ALERT-%d" and "TR-%d". A screen holding only the numeric id
        // must arrive at the identical string or the two halves of one page disagree.
        expect(formatAlertId(1)).toBe('ALERT-1');
        expect(formatTransferId(123)).toBe('TR-123');
    });
});

describe('an account number', () => {
    it('groups in fours, which is how an IBAN is printed and where it may break', () => {
        expect(formatIban('CZ6508000000192000145399')).toBe(
            'CZ65 0800 0000 1920 0014 5399',
        );
    });

    it('does not space an already spaced one twice', () => {
        expect(formatIban('CZ65 0800 0000 1920 0014 5399')).toBe(
            'CZ65 0800 0000 1920 0014 5399',
        );
        expect(formatIban(`CZ65${NBSP}0800`)).toBe('CZ65 0800');
    });

    it('leaves a last group shorter than four alone rather than padding it', () => {
        expect(formatIban('CZ650800000019200014539')).toBe('CZ65 0800 0000 1920 0014 539');
    });

    it.each([null, undefined, ''])('draws the empty value for %s', (missing) => {
        expect(formatIban(missing)).toBe(EMPTY_VALUE);
    });
});
