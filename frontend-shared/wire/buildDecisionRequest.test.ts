import { describe, it, expect } from 'vitest';
import { buildDecisionRequest } from './fraud';

/**
 * The body a decision is sent as, and specifically which keys are not in it.
 *
 * Both optional fields mean "leave what is already there alone" by being absent, and an empty
 * string is a different instruction to the server. `note` appends one entry to the alert's journal,
 * so a blank one files an empty paragraph on every press. `comment` is what the analyst concluded
 * about this verdict and it is the sentence the customer is shown when the verdict is a refusal;
 * blank, it writes over what a colleague put there.
 *
 * Both desks built this inline as `box.trim() || undefined`, which leaves the key present holding
 * undefined and relies on JSON.stringify dropping it on the way out. That is correct over the wire
 * and it means the object in front of a reader and the bytes the server gets are two different
 * things, which is exactly the gap a shaping test has to close. So the assertions below are about
 * the key, not about the value: `toEqual` treats a missing key and an undefined one as the same
 * object and would pass either way.
 */

const CONCLUSION = 'Beneficiary matches a mule account reported last week.';
const ENTRY = 'Called the customer, no answer.';

describe('the verdict itself', () => {
    it.each(['APPROVE', 'DECLINE', 'ANNOTATE'] as const)('rides on every body, here %s', (kind) => {
        expect(buildDecisionRequest(kind, '', '').decision).toBe(kind);
    });

    it('is never trimmed and never dropped, being a token rather than typing', () => {
        const body = buildDecisionRequest('ANNOTATE', '', '');
        expect(Object.keys(body)).toEqual(['decision']);
    });
});

describe('a box nobody typed in', () => {
    it('leaves the comment key off the body rather than sending an empty string', () => {
        const body = buildDecisionRequest('APPROVE', '', ENTRY);
        expect('comment' in body).toBe(false);
    });

    it('leaves the note key off the body rather than sending an empty string', () => {
        const body = buildDecisionRequest('APPROVE', CONCLUSION, '');
        expect('note' in body).toBe(false);
    });

    it('sends the verdict alone when both boxes are empty', () => {
        expect(Object.keys(buildDecisionRequest('APPROVE', '', ''))).toEqual(['decision']);
    });

    it.each([' ', '   ', '\n', '\t\t'])(
        'reads %o as untyped, in both boxes, so an accidental keystroke files nothing',
        (only) => {
            const body = buildDecisionRequest('ANNOTATE', only, only);
            expect('comment' in body).toBe(false);
            expect('note' in body).toBe(false);
        },
    );

    it('survives the round trip to JSON without either key appearing', () => {
        // The end of the argument. Whatever the object looks like, this is what leaves the browser.
        const wire = JSON.stringify(buildDecisionRequest('APPROVE', '', ''));
        expect(wire).toBe('{"decision":"APPROVE"}');
    });
});

describe('a box somebody typed in', () => {
    it('carries the comment, trimmed', () => {
        const body = buildDecisionRequest('DECLINE', `  ${CONCLUSION}\n`, '');
        expect(body.comment).toBe(CONCLUSION);
    });

    it('carries the note, trimmed', () => {
        const body = buildDecisionRequest('ANNOTATE', '', `\t${ENTRY}  `);
        expect(body.note).toBe(ENTRY);
    });

    it('carries both at once, which is one press and not two', () => {
        expect(buildDecisionRequest('DECLINE', CONCLUSION, ENTRY)).toEqual({
            decision: 'DECLINE',
            comment: CONCLUSION,
            note: ENTRY,
        });
    });

    it('keeps the two apart: the conclusion is not filed as the journal entry', () => {
        // They were one field once. `notes` carried the whole journal and the comment was called
        // `reason`, and the alert then printed the analyst's sentence joined to the bank's own.
        const body = buildDecisionRequest('DECLINE', CONCLUSION, ENTRY);
        expect(body.comment).not.toBe(body.note);
    });

    it('sends a comment on a verdict that is not a refusal', () => {
        // It rides with all three presses. Nothing may narrow it back to the refusal: it is what
        // the analyst concluded, and on an approval that conclusion is the record of why the money
        // was let go.
        expect(buildDecisionRequest('APPROVE', CONCLUSION, '').comment).toBe(CONCLUSION);
    });
});

describe('the keys the wire has and no others', () => {
    it('offers nothing the server retired, whatever the boxes hold', () => {
        // `tags` and `assignee` left this body and are not coming back, and `notes` was replaced
        // by `note`, which is a different field rather than a rename. `notes` sent today is
        // ignored by the server, so a desk that still sent it would file nothing and say it had.
        const keys = Object.keys(buildDecisionRequest('DECLINE', CONCLUSION, ENTRY));
        for (const retired of ['tags', 'assignee', 'notes', 'reason']) {
            expect(keys).not.toContain(retired);
        }
    });

    it('never grows a key past the three the request declares', () => {
        const keys = Object.keys(buildDecisionRequest('DECLINE', CONCLUSION, ENTRY)).sort();
        expect(keys).toEqual(['comment', 'decision', 'note']);
    });
});
