import { describe, it, expect } from 'vitest';
import {
    DEFAULT_PAGE_SIZE,
    MAX_PAGE_SIZE,
    SHOW_MORE,
    SHOW_MORE_BUSY,
    appendPage,
    applyPaging,
    hasMore,
    nextPage,
    showingLine,
} from './paging';

/**
 * Five lists across two applications grow the same foot: a Show more button, a line saying how
 * much is on screen, and the arithmetic that decides both. None of it had a test, which is the
 * state a module is in when it is about to be reimplemented in a screen by somebody who did not
 * know it was here.
 */

describe('how much of the list is on screen', () => {
    it('names the second number only while there is one worth naming', () => {
        expect(showingLine(25, 137)).toBe('Showing: 25 of 137');
        expect(showingLine(137, 137)).toBe('Showing: 137');
    });

    it('stands in a row of labels, so it carries a colon and no full stop', () => {
        // Its neighbours in that strip are "New: 12" and "Cleared: 122". A stop here would be the
        // one cell of four that ends a sentence.
        for (const line of [showingLine(25, 137), showingLine(137, 137)]) {
            expect(line).toContain(': ');
            expect(line.endsWith('.')).toBe(false);
        }
    });

    it('prints a row count plain, which the money formatter would not', () => {
        // Through formatCzech this would read "10,00", and the thousands would be divided with a
        // non-breaking space. These are rows.
        expect(showingLine(10, 10)).toBe('Showing: 10');
        expect(showingLine(1000, 2000)).toBe('Showing: 1000 of 2000');
    });

    it('cannot say it holds more than there is, however the two numbers arrive', () => {
        // A total that has shrunk under a list already on screen is reachable: rows are deleted
        // between two requests. "Showing: 12 of 10" is not.
        expect(showingLine(12, 10)).toBe('Showing: 12');
    });

    it('counts whole rows, because half a row is not on screen', () => {
        expect(showingLine(2.7, 10)).toBe('Showing: 2 of 10');
    });

    it('says nothing about an empty list, which is the screen own sentence', () => {
        expect(showingLine(0, 0)).toBe('Showing: 0');
    });
});

describe('whether the button is drawn at all', () => {
    it.each([
        [25, 137, true],
        [137, 137, false],
        [0, 0, false],
        [0, 1, true],
    ])('holds %i of %i and answers %s', (shown, total, expected) => {
        expect(hasMore(shown, total)).toBe(expected);
    });

    it('is false once a list has outgrown the total it was promised', () => {
        expect(hasMore(12, 10)).toBe(false);
    });

    it('agrees with the line beside it: no button exactly when there is no second number', () => {
        for (const [shown, total] of [
            [25, 137],
            [137, 137],
            [12, 10],
            [0, 0],
        ]) {
            expect(showingLine(shown, total).includes(' of ')).toBe(hasMore(shown, total));
        }
    });
});

describe('the page to ask for next', () => {
    it('is counted from the page that arrived and not from the rows on screen', () => {
        // The distinction has one witness and this is it: a page whose rows were all dropped as
        // repeats. Derived from the row count the request would repeat page 1 forever.
        const arrived = { items: [], page: 1, size: 25, total: 137 };
        expect(nextPage(arrived)).toBe(2);
    });

    it('follows the size the server echoed rather than the size that was asked for', () => {
        expect(nextPage({ items: [], page: 0, size: 10, total: 40 })).toBe(1);
    });
});

describe('adding a page to the rows already held', () => {
    const held = [{ id: 1 }, { id: 2 }, { id: 3 }];

    it('drops the row offset paging handed back twice', () => {
        // A payment created between the two requests pushes a row off page one onto page two.
        // Kept, it is one payment drawn twice under one React key.
        expect(appendPage(held, [{ id: 3 }, { id: 4 }])).toEqual([
            { id: 1 },
            { id: 2 },
            { id: 3 },
            { id: 4 },
        ]);
    });

    it('keeps the order the rows arrived in, the held ones first', () => {
        expect(appendPage([{ id: 5 }], [{ id: 9 }, { id: 7 }]).map((r) => r.id)).toEqual([5, 9, 7]);
    });

    it('drops a repeat inside the incoming page too, not only one against the held rows', () => {
        expect(appendPage([], [{ id: 1 }, { id: 1 }]).map((r) => r.id)).toEqual([1]);
    });

    it('leaves the array it was given alone, so a stale render cannot see a half merged list', () => {
        const before = [{ id: 1 }];
        appendPage(before, [{ id: 2 }]);
        expect(before).toEqual([{ id: 1 }]);
    });

    it('answers a page of nothing with the rows already held', () => {
        expect(appendPage(held, [])).toEqual(held);
    });

    it('keeps the row it already had rather than the one that arrived under the same id', () => {
        // Not an arbitrary choice between two copies: the held row is the one on screen, and
        // replacing it would redraw a row under the reader for a change they cannot see.
        const merged = appendPage([{ id: 1, state: 'NEW' }], [{ id: 1, state: 'OK' }]);
        expect(merged).toEqual([{ id: 1, state: 'NEW' }]);
    });
});

describe('what goes on the query string', () => {
    it('writes both numbers and hands the same parameters back', () => {
        const params = new URLSearchParams();
        expect(applyPaging(params, 2, 25)).toBe(params);
        expect(params.get('page')).toBe('2');
        expect(params.get('size')).toBe('25');
    });

    it('leaves the parameters the caller had already built, which is why it takes them', () => {
        // The alert queue writes six of its own before it gets here. A second URLSearchParams to
        // merge is how one of the six goes missing.
        const params = new URLSearchParams();
        params.set('state', 'NEW');
        params.append('excludeTransferStatus', 'DECLINED');
        applyPaging(params, 0, DEFAULT_PAGE_SIZE);
        expect(params.get('state')).toBe('NEW');
        expect(params.getAll('excludeTransferStatus')).toEqual(['DECLINED']);
    });

    it('clamps a size the server would refuse, so a screen gets rows and not an error box', () => {
        const params = applyPaging(new URLSearchParams(), 0, MAX_PAGE_SIZE + 1);
        expect(params.get('size')).toBe(String(MAX_PAGE_SIZE));
    });

    it('asks for one row rather than none, because a size of zero is a mistake in a screen', () => {
        expect(applyPaging(new URLSearchParams(), 0, 0).get('size')).toBe('1');
        expect(applyPaging(new URLSearchParams(), 0, -5).get('size')).toBe('1');
    });

    it('never asks for a page before the first', () => {
        expect(applyPaging(new URLSearchParams(), -1, 25).get('page')).toBe('0');
    });

    it('sends the default through untouched, which is what makes it the default', () => {
        expect(DEFAULT_PAGE_SIZE).toBeLessThanOrEqual(MAX_PAGE_SIZE);
        expect(applyPaging(new URLSearchParams(), 0, DEFAULT_PAGE_SIZE).get('size')).toBe(
            String(DEFAULT_PAGE_SIZE),
        );
    });
});

describe('the two words on the button', () => {
    it('does not widen under the pointer that just pressed it', () => {
        // "Loading more" was the alternative and it is longer than the label it replaces, so the
        // button grows and the control moves out from under the finger.
        expect(SHOW_MORE_BUSY.length).toBeLessThanOrEqual(SHOW_MORE.length);
    });
});
