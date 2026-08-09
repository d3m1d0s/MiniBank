import { describe, it, expect } from 'vitest';
import { amountRangeProblem } from '@shared/alertFilters';

/**
 * An empty queue is how the alert endpoint reports "nothing matched". So a filter that cannot
 * match anything must never reach it: otherwise a backwards range is indistinguishable from a
 * quiet afternoon, and the counters beside the list go on reporting the whole queue.
 */

const READABLE = { min: false, max: false };

describe('ranges that can be sent', () => {
    it('passes when nothing is filtered', () => {
        expect(amountRangeProblem({}, READABLE)).toBeNull();
    });

    it.each([
        [{ minAmount: '100', maxAmount: '500' }, 'an ordinary range'],
        [{ minAmount: '100', maxAmount: '100' }, 'a single-value range'],
        [{ minAmount: '100' }, 'a floor with no ceiling'],
        [{ maxAmount: '500' }, 'a ceiling with no floor'],
        [{ minAmount: '0' }, 'a zero floor, which is a filter and not an absence'],
        [{ minAmount: '0', maxAmount: '0' }, 'zero to zero'],
        [{ minAmount: '0.01', maxAmount: '0.02' }, 'hellers'],
    // The second element of each row is only there to name the case in the report. It has to be
    // accepted as a parameter for the row to type-check, and the leading underscore is what
    // keeps noUnusedParameters from objecting to accepting it.
    ])('passes %o - %s', (filters, _case) => {
        expect(amountRangeProblem(filters, READABLE)).toBeNull();
    });

    it('ignores an empty box rather than treating it as a bound', () => {
        expect(amountRangeProblem({ minAmount: '', maxAmount: '' }, READABLE)).toBeNull();
    });
});

describe('ranges that cannot match anything', () => {
    it('refuses a reversed range and names both numbers, which the server cannot', () => {
        const problem = amountRangeProblem({ minAmount: '500', maxAmount: '100' }, READABLE);
        expect(problem).not.toBeNull();
        expect(problem).toContain('500');
        expect(problem).toContain('100');
    });

    it('refuses a negative floor', () => {
        expect(amountRangeProblem({ minAmount: '-1' }, READABLE))
            .toContain('smallest amount cannot be negative');
    });

    it('refuses a negative ceiling', () => {
        expect(amountRangeProblem({ maxAmount: '-0.01' }, READABLE))
            .toContain('largest amount cannot be negative');
    });

    it('refuses a bound that is not a number at all', () => {
        // Not reachable through a number input, and the function is reachable without one.
        expect(amountRangeProblem({ minAmount: 'abc' }, READABLE)).not.toBeNull();
        expect(amountRangeProblem({ maxAmount: 'abc' }, READABLE)).not.toBeNull();
    });
});

describe('a box the browser could not read', () => {
    // This is the trap that would have made type="number" worse than free text: an unreadable
    // value reports as the empty string, the query builder drops the parameter, and the analyst
    // is handed the whole queue looking like a filtered one.
    it.each([
        [{ min: true, max: false }, 'the floor'],
        [{ min: false, max: true }, 'the ceiling'],
        [{ min: true, max: true }, 'both'],
    ])('refuses when %o could not be read - %s', (unreadable, _case) => {
        expect(amountRangeProblem({}, unreadable)).toContain('could not be read');
    });

    it('says so even when the values left behind look like a perfectly good range', () => {
        // The empty string an unreadable box reports is exactly what a cleared box reports, so
        // the filters alone cannot tell the two apart. Only the flag can.
        const problem = amountRangeProblem(
            { minAmount: '100', maxAmount: '500' },
            { min: false, max: true },
        );
        expect(problem).toContain('could not be read');
    });

    it('is reported ahead of a reversed range, because it is the more basic complaint', () => {
        const problem = amountRangeProblem(
            { minAmount: '500', maxAmount: '100' },
            { min: true, max: false },
        );
        expect(problem).toContain('could not be read');
    });
});
