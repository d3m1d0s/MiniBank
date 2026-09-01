import { describe, it, expect } from 'vitest';
import { decisionAllowed } from './fraud';
import type { FraudDecision } from './fraud';

/**
 * Which of the three verdicts an alert in a given state will take.
 *
 * The rule used to live in six `disabled=` expressions, three per desk, and the two desks agreed
 * only because the same person wrote both on the same afternoon. Nothing checked it. Each of the
 * three lines is a rule about money: one releases a held payment, one sends the customer a sentence
 * that cannot be withdrawn, and one does nothing at all to either.
 *
 * The alert states are the three the queue counts, from queueCounterCells: NEW is undecided,
 * SUSPICIOUS is recorded fraud and OK is cleared. Every case below names one of them, because the
 * interesting failures are not "some other state" but the two decided ones, which look alike to a
 * reader and are not alike to APPROVE.
 */

const STATES = ['NEW', 'SUSPICIOUS', 'OK'] as const;
const DECIDED = ['SUSPICIOUS', 'OK'] as const;
const WRITTEN = 'Card used from two countries within the hour.';

describe('releasing the payment', () => {
    it('is offered on an alert nobody has decided', () => {
        expect(decisionAllowed('APPROVE', { state: 'NEW' }, '')).toBe(true);
    });

    it.each(DECIDED)('is refused once a verdict is recorded, here %s', (state) => {
        // Not cosmetic. The server refuses a second verdict, and its refusal arrives as the general
        // validation sentence, which does not say that a colleague got there first.
        expect(decisionAllowed('APPROVE', { state }, WRITTEN)).toBe(false);
    });

    it('does not care what is in the comment box, in either direction', () => {
        expect(decisionAllowed('APPROVE', { state: 'NEW' }, '')).toBe(true);
        expect(decisionAllowed('APPROVE', { state: 'NEW' }, WRITTEN)).toBe(true);
        expect(decisionAllowed('APPROVE', { state: 'OK' }, WRITTEN)).toBe(false);
    });
});

describe('refusing the payment', () => {
    it('is offered on a new alert once something is written', () => {
        expect(decisionAllowed('DECLINE', { state: 'NEW' }, WRITTEN)).toBe(true);
    });

    it('is refused on an alert that already records fraud, which is what it would record', () => {
        expect(decisionAllowed('DECLINE', { state: 'SUSPICIOUS' }, WRITTEN)).toBe(false);
    });

    it('is offered on a cleared alert, unlike releasing, because clearing can be reconsidered', () => {
        // The asymmetry is the point of having both cases here. OK and SUSPICIOUS are both decided
        // states and one of them still takes this verdict.
        expect(decisionAllowed('DECLINE', { state: 'OK' }, WRITTEN)).toBe(true);
    });

    it('is refused while the comment box is empty, whatever the state', () => {
        expect(decisionAllowed('DECLINE', { state: 'NEW' }, '')).toBe(false);
        expect(decisionAllowed('DECLINE', { state: 'OK' }, '')).toBe(false);
    });

    it.each([' ', '   ', '\n', '\t ', ' '])(
        'reads %o as an empty box, so a press cannot send the customer whitespace',
        (only) => {
            // What the customer is shown is this string. A refusal whose reason is one space is
            // the failure the box exists to prevent, and it is a keystroke away from an empty box.
            expect(decisionAllowed('DECLINE', { state: 'NEW' }, only)).toBe(false);
        },
    );

    it('counts a comment that is padded but not empty', () => {
        expect(decisionAllowed('DECLINE', { state: 'NEW' }, `  ${WRITTEN}  `)).toBe(true);
    });
});

describe('adding a note', () => {
    it.each(STATES)('is offered in every state, here %s', (state) => {
        // It takes no verdict and moves no money, so there is no state it is wrong in. An alert
        // decided last week is exactly the one somebody comes back to write on.
        expect(decisionAllowed('ANNOTATE', { state }, '')).toBe(true);
        expect(decisionAllowed('ANNOTATE', { state }, WRITTEN)).toBe(true);
    });
});

describe('a state nobody planned for', () => {
    it.each(['', 'new', 'PENDING', 'null'])(
        'refuses the verdict that moves money and offers the one that does not, for %o',
        (state) => {
            // A fourth state added at the server reaches this function as a string. The safe
            // reading of an unknown state is that the alert is not the untouched one, and the
            // spelling matters: a desk matching case-insensitively would release on `new`.
            expect(decisionAllowed('APPROVE', { state }, WRITTEN)).toBe(false);
            expect(decisionAllowed('ANNOTATE', { state }, '')).toBe(true);
        },
    );
});

describe('the three verdicts as a set', () => {
    const KINDS: readonly FraudDecision[] = ['APPROVE', 'DECLINE', 'ANNOTATE'];

    it('answers a boolean for every one of them, so no press falls through undecided', () => {
        // The function is a switch over a closed union. A fourth verdict added to the union and
        // not to the switch stops the build; a fourth one cast in from elsewhere would fall out
        // as undefined, and `disabled={!undefined}` is a live button.
        for (const kind of KINDS) {
            for (const state of STATES) {
                expect(typeof decisionAllowed(kind, { state }, WRITTEN)).toBe('boolean');
            }
        }
    });

    it('offers at most one destructive verdict on a decided alert', () => {
        // Both desks draw all three buttons always and grey the dead ones, rather than hiding
        // them, so this is what an analyst sees rather than an internal invariant.
        expect(decisionAllowed('APPROVE', { state: 'SUSPICIOUS' }, WRITTEN)).toBe(false);
        expect(decisionAllowed('DECLINE', { state: 'SUSPICIOUS' }, WRITTEN)).toBe(false);
    });
});
