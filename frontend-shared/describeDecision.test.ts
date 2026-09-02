import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { DECISION_RESULT_TITLE, DECISION_TITLE, describeDecision } from './glossary';
import type { AlertDetail, FraudDecision } from './fraud';

/**
 * What the analyst is told a press did.
 *
 * The whole point of the function is that the sentence is read off the payment the server sent
 * back and not off the button. Deriving it from the button was safe only while every decision did
 * the one thing its label said: a DECLINE on a payment that has already gone is now accepted and
 * records the verdict without stopping anything, and "Transfer declined" for that would tell the
 * analyst the money was held when it is gone.
 *
 * Both desks wrote these sentences themselves and two had already drifted apart before they were
 * moved here, so the same outcome was announced in two different sets of words on the two
 * platforms. Nothing was holding either of them to anything.
 */

const ACTIONS: FraudDecision[] = ['APPROVE', 'DECLINE', 'ANNOTATE'];
const STATUSES = ['CREATED', 'HELD_FOR_REVIEW', 'WAITING_AUTH', 'SENT', 'DECLINED'];

describe('clearing an alert', () => {
    it('says the payment goes back to the customer while it is still waiting on one', () => {
        expect(describeDecision('APPROVE', 'WAITING_AUTH')).toBe(
            'Alert cleared. The payment is released to the customer to confirm; no money has moved.',
        );
    });

    it('promises no money has moved, which is the fact the press turns on', () => {
        expect(describeDecision('APPROVE', 'WAITING_AUTH')).toContain('no money has moved');
    });

    it.each(['SENT', 'DECLINED', 'CREATED', 'HELD_FOR_REVIEW'])(
        'says there was nothing to release when the payment was already %s',
        (status) => {
            expect(describeDecision('APPROVE', status)).toContain('nothing to release');
            expect(describeDecision('APPROVE', status)).not.toContain('released to the customer');
        },
    );

    it('names what the payment had already become, in the analyst own vocabulary', () => {
        expect(describeDecision('APPROVE', 'SENT')).toBe(
            'Alert cleared. The transfer was already sent, so there was nothing to release.',
        );
        // Under review, not HELD_FOR_REVIEW: the sentence speaks the status rather than printing
        // it, and this is the only place in either application where that is true.
        expect(describeDecision('APPROVE', 'HELD_FOR_REVIEW')).toContain('already under review');
    });
});

describe('recording confirmed fraud', () => {
    it('says out loud that a payment already gone has NOT been reversed', () => {
        // The worse lie the old wording told: "Transfer declined" for money that is gone.
        expect(describeDecision('DECLINE', 'SENT')).toBe(
            'Recorded as confirmed fraud. The payment had already been sent and has NOT been reversed.',
        );
    });

    it('does not claim the alert was cleared', () => {
        for (const status of STATUSES) {
            expect(describeDecision('DECLINE', status)).not.toContain('cleared');
        }
    });

    it('names the state the transfer is left in when the payment was stopped in time', () => {
        expect(describeDecision('DECLINE', 'DECLINED')).toBe(
            'Alert recorded as confirmed fraud, and the transfer is declined.',
        );
    });
});

describe('saving without deciding', () => {
    it.each(STATUSES)('takes no decision whatever the payment is doing, here %s', (status) => {
        const said = describeDecision('ANNOTATE', status);
        expect(said).toContain('No decision was taken');
        expect(said).toContain('the transfer is unchanged');
    });

    it('promises nothing this press cannot do', () => {
        // It used to promise two. Tags cannot be written over the decision route at all, and the
        // assignee is written by a route of its own that no button in the panel calls.
        const said = describeDecision('ANNOTATE', 'HELD_FOR_REVIEW');
        expect(said).not.toMatch(/tag/i);
        expect(said).not.toMatch(/assign/i);
    });

    it('says the entry was saved on the alert without naming which of the two it was', () => {
        // The press may write the comment, the case note, both or neither, so naming one would
        // announce the wrong one half the time.
        expect(describeDecision('ANNOTATE', 'SENT')).toContain('Saved on the alert');
    });
});

describe('the sentence follows the answer, not the button', () => {
    it('gives one press two different sentences for two different answers', () => {
        // The single case this function exists for. Same action, same analyst, same click.
        expect(describeDecision('DECLINE', 'SENT')).not.toBe(
            describeDecision('DECLINE', 'HELD_FOR_REVIEW'),
        );
        expect(describeDecision('APPROVE', 'WAITING_AUTH')).not.toBe(
            describeDecision('APPROVE', 'SENT'),
        );
    });

    it('reads the status off a body the server really sent', () => {
        // A hand written status would prove the branch, not that the branch is reachable with
        // what arrives. The captured alert is held for review, so approving it releases nothing.
        const detail: AlertDetail = JSON.parse(
            readFileSync(new URL('./__fixtures__/fraud-alert-detail.json', import.meta.url), 'utf8'),
        );
        expect(detail.transfer.status).toBe('HELD_FOR_REVIEW');
        expect(describeDecision('APPROVE', detail.transfer.status)).toContain(
            'nothing to release',
        );
        expect(describeDecision('APPROVE', detail.transfer.status)).not.toContain(
            'no money has moved',
        );
    });
});

describe('what no sentence may contain', () => {
    it.each(ACTIONS)('prints no raw enum at the analyst after a %s', (action) => {
        for (const status of STATUSES) {
            // The sentence used to interpolate the token: "the transfer is WAITING_AUTH".
            expect(describeDecision(action, status)).not.toMatch(/[A-Z]{2,}_[A-Z]{2,}/);
        }
    });

    it.each(ACTIONS)('ends in a full stop after a %s, being prose and not a label', (action) => {
        for (const status of STATUSES) {
            expect(describeDecision(action, status).endsWith('.')).toBe(true);
        }
    });
});

describe('the word over the sentence', () => {
    it('is not the heading of the panel it sits inside', () => {
        // Two headings reading the same word, one inside the other, name nothing.
        expect(DECISION_RESULT_TITLE).not.toBe(DECISION_TITLE);
    });
});
