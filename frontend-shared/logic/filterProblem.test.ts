import { describe, it, expect } from 'vitest';
import { filterProblem } from './alertFilters';
import type { AmountBoxes } from './alertFilters';

/**
 * The second pair, and the order the complaints come in.
 *
 * The amount pair has been checked here since the box was a number input; the queue is narrowed by
 * a range of raising times as well, and that pair went to the server unchecked. It goes wrong the
 * same way and it is answered the same way: an empty queue is how the endpoint reports "nothing
 * matched", so a range that cannot match anything must never reach it, or a backwards filter is
 * indistinguishable from a quiet afternoon.
 *
 * The amount cases live beside the ones in the application that owns the screen. What is pinned
 * here is what the widening added: the dates, and which complaint is spoken when more than one is
 * true at once.
 */

const READABLE: AmountBoxes = { min: false, max: false };

/** An instant as the server's own parser takes one. A date box writes the first ten characters. */
const AUGUST = '2026-08-01T00:00:00Z';
const SEPTEMBER = '2026-09-01T00:00:00Z';

describe('date ranges that can be sent', () => {
    it.each([
        [{}, 'no dates at all'],
        [{ createdFrom: AUGUST }, 'an earliest with no latest'],
        [{ createdTo: SEPTEMBER }, 'a latest with no earliest'],
        [{ createdFrom: AUGUST, createdTo: SEPTEMBER }, 'an ordinary range'],
        [{ createdFrom: AUGUST, createdTo: AUGUST }, 'one instant, which both bounds admit'],
        [{ createdFrom: '', createdTo: '' }, 'two boxes nobody filled in'],
    ])('passes %o - %s', (filters, _case) => {
        expect(filterProblem(filters, READABLE)).toBeNull();
    });

    it('takes an offset as readily as a Z, both being instants', () => {
        expect(
            filterProblem(
                { createdFrom: '2026-08-01T00:00:00+02:00', createdTo: SEPTEMBER },
                READABLE,
            ),
        ).toBeNull();
    });

    it('takes the fractional seconds the server sends back on every timestamp', () => {
        // Every createdAt on this wire arrives as 2026-08-29T20:20:50.290980Z, so a filter built
        // from a row the analyst is looking at has to be sendable.
        expect(
            filterProblem({ createdFrom: '2026-08-29T20:20:50.290980Z' }, READABLE),
        ).toBeNull();
    });
});

describe('a date range that cannot match anything', () => {
    it('is refused and names both dates, which the server cannot', () => {
        const problem = filterProblem(
            { createdFrom: SEPTEMBER, createdTo: AUGUST },
            READABLE,
        );
        expect(problem).toBe(
            `The earliest date (${SEPTEMBER}) is after the latest (${AUGUST}).`,
        );
    });

    it('names them as they were typed, so the analyst can see which box to change', () => {
        const problem = filterProblem(
            { createdFrom: SEPTEMBER, createdTo: AUGUST },
            READABLE,
        );
        expect(problem).toContain(SEPTEMBER);
        expect(problem).toContain(AUGUST);
    });
});

describe('a date the server would refuse to parse', () => {
    it.each([
        ['2026-08-30', 'what a date input writes, with no zone on it at all'],
        ['yesterday', 'a word'],
        ['30/08/2026', 'the Czech reading order, which is not a timestamp'],
        ['2026-08-30T12:00', 'a local time, which names no instant'],
    ])('refuses %o as the earliest - %s', (raw, _case) => {
        // Instant.parse takes nothing else, and over the wire its refusal is only "the request
        // contains invalid or missing values".
        expect(filterProblem({ createdFrom: raw }, READABLE)).toContain(
            'The earliest date could not be read',
        );
    });

    it('refuses the same value in the latest box, and says which box', () => {
        expect(filterProblem({ createdTo: '2026-08-30' }, READABLE)).toContain(
            'The latest date could not be read',
        );
    });

    it('teaches the shape rather than naming the standard', () => {
        expect(filterProblem({ createdFrom: 'yesterday' }, READABLE)).toContain(
            '2026-08-30T00:00:00Z',
        );
    });

    it('does not let an unreadable half slip through the comparison beside it', () => {
        // The trap this branch exists for: parsed to NaN, the pair compares false both ways and a
        // range nobody can read is sent as a range that is fine.
        expect(filterProblem({ createdFrom: 'yesterday', createdTo: AUGUST }, READABLE))
            .not.toBeNull();
        expect(filterProblem({ createdFrom: SEPTEMBER, createdTo: 'tomorrow' }, READABLE))
            .not.toBeNull();
    });
});

describe('which complaint is spoken when more than one is true', () => {
    // One filter, everything wrong with it at once. Each case below removes the complaint above it
    // and reads what is left, which is what makes this a total order rather than three assertions.
    const everything = {
        createdFrom: SEPTEMBER,
        createdTo: AUGUST,
        minAmount: '500',
        maxAmount: '100',
    };

    it('speaks the box the browser could not read first, its evidence being the one that is gone', () => {
        expect(filterProblem(everything, { min: true, max: false })).toContain(
            'could not be read',
        );
    });

    it('speaks the dates next, because they narrow what the amounts are asked about', () => {
        expect(filterProblem(everything, READABLE)).toContain('The earliest date');
    });

    it('speaks the reversed amounts last, where the reader eye already is', () => {
        const { createdFrom: _from, createdTo: _to, ...amountsOnly } = everything;
        expect(filterProblem(amountsOnly, READABLE)).toBe(
            'The smallest amount (500) is above the largest (100).',
        );
    });

    it('holds the same order for a date that cannot be read as for one that is backwards', () => {
        expect(
            filterProblem({ createdFrom: 'yesterday', minAmount: '500', maxAmount: '100' }, READABLE),
        ).toContain('The earliest date could not be read');
    });

    it('answers null once none of the three is true, so the order is an order and not a trap', () => {
        expect(
            filterProblem(
                {
                    createdFrom: AUGUST,
                    createdTo: SEPTEMBER,
                    minAmount: '100',
                    maxAmount: '500',
                },
                READABLE,
            ),
        ).toBeNull();
    });
});
