import { describe, it, expect } from 'vitest';
import {
    alertStateLabel,
    alertStateTone,
    authMethodLabel,
    CUSTOMER_HISTORY_TITLE,
    decisionLabel,
    describeDeclineReason,
    roleLabel,
    transferStatusLabel,
    transferStatusTone,
} from '@shared/glossary';
import { FIELD_LABEL, HISTORY_FIELDS } from '@shared/fields';
import type { HistoryItem } from '@shared/fraud';

/**
 * Every value below is a Java enum in cz.vsb.minibank.domain. The lists here are the enums in
 * full, deliberately: a value that stops being translated must fail a test rather than start
 * appearing on a screen in capitals with an underscore in it.
 */

const TRANSFER_STATUSES = ['CREATED', 'HELD_FOR_REVIEW', 'WAITING_AUTH', 'SENT', 'DECLINED'];
const ALERT_STATES = ['NEW', 'OK', 'SUSPICIOUS'];
const ROLES = ['CUSTOMER', 'FRAUD_ANALYST', 'OPERATIONS', 'MANAGEMENT'];

/** A word a person would read: no capitals run, no underscore. */
function readable(text: string): boolean {
    return text.length > 0 && !text.includes('_') && text !== text.toUpperCase();
}

describe('no raw enum reaches a person', () => {
    it.each(TRANSFER_STATUSES)('translates the transfer status %s for both audiences', (s) => {
        expect(readable(transferStatusLabel(s, 'customer'))).toBe(true);
        expect(readable(transferStatusLabel(s, 'analyst'))).toBe(true);
    });

    it.each(ALERT_STATES)('translates the alert state %s', (s) => {
        expect(readable(alertStateLabel(s))).toBe(true);
    });

    it.each(ROLES)('translates the role %s', (r) => {
        expect(readable(roleLabel(r))).toBe(true);
    });

    it.each(['APPROVE', 'DECLINE', 'ANNOTATE'])('translates the decision %s', (d) => {
        expect(readable(decisionLabel(d))).toBe(true);
    });

    it.each(['OTP', 'CARD'])('translates the authorization method %s', (m) => {
        expect(readable(authMethodLabel(m))).toBe(true);
    });
});

describe('the wording one screen had already settled', () => {
    it('keeps the two sentences the waiting list was written with', () => {
        // These two were the only translated statuses in either application. They are the
        // standard the rest of this table was built out from, not a second wording beside them.
        expect(transferStatusLabel('HELD_FOR_REVIEW', 'customer')).toBe('Under review');
        expect(transferStatusLabel('WAITING_AUTH', 'customer')).toBe('Waiting for your code');
    });

    it('does not tell an analyst to check their own phone', () => {
        // The one status that genuinely needs two sentences, and the reason the audience is a
        // required parameter rather than a defaulted one.
        expect(transferStatusLabel('WAITING_AUTH', 'analyst')).not.toContain('your');
        expect(transferStatusLabel('WAITING_AUTH', 'analyst')).toContain('customer');
    });

    it('says it in a length the analyst status column can print on one line', () => {
        // The sentence the analyst gets is the longest in this table and lands in the narrowest
        // place it is read: 194px of the workstation's history, where 31 characters wrapped to two
        // lines while every neighbouring row stayed on one. 24 is the room that column has. The
        // literal is deliberately not pinned here, only what has to remain true of it.
        expect(transferStatusLabel('WAITING_AUTH', 'analyst').length).toBeLessThanOrEqual(24);
    });

    it('says the same thing to both where the fact is the same', () => {
        for (const status of ['CREATED', 'HELD_FOR_REVIEW', 'SENT', 'DECLINED']) {
            expect(transferStatusLabel(status, 'customer')).toBe(
                transferStatusLabel(status, 'analyst'),
            );
        }
    });

    it('names the alert states after what writing them means', () => {
        // Approving writes OK and declining writes SUSPICIOUS, so these are verdicts and not
        // impressions. The button that writes the second one says "record confirmed fraud".
        expect(alertStateLabel('OK')).toBe('Cleared');
        expect(alertStateLabel('SUSPICIOUS')).toBe('Confirmed fraud');
        expect(alertStateLabel('NEW')).toBe('New');
    });
});

describe('a value this client has never heard of', () => {
    it('prints it as itself, so an enum added on the server shows up', () => {
        // Blanking it would make the row look empty and the addition invisible. Foreign text on
        // a screen is a bug report; a missing cell is not.
        expect(transferStatusLabel('RETURNED', 'customer')).toBe('RETURNED');
        expect(alertStateLabel('ESCALATED')).toBe('ESCALATED');
        expect(roleLabel('AUDITOR')).toBe('AUDITOR');
        expect(decisionLabel('REOPEN')).toBe('REOPEN');
        expect(authMethodLabel('BIOMETRIC')).toBe('BIOMETRIC');
    });

    it('is marked out rather than left to join the quiet rows', () => {
        expect(transferStatusTone('RETURNED')).toBe('pending');
        expect(alertStateTone('ESCALATED')).toBe('pending');
    });

    it.each([null, undefined, ''])('renders nothing for %s', (missing) => {
        expect(transferStatusLabel(missing, 'customer')).toBe('');
        expect(alertStateLabel(missing)).toBe('');
        expect(decisionLabel(missing)).toBe('');
        expect(roleLabel(missing)).toBe('');
        expect(authMethodLabel(missing)).toBe('');
    });
});

describe('the decision token that was renamed', () => {
    it('reads a row written before the rename the same as one written after', () => {
        // REQUEST_CONFIRMATION described something the action never did and was renamed to
        // ANNOTATE, but the old spelling is in the database forever. An alert decided last
        // month must not stop having a verdict because the word for it changed.
        expect(decisionLabel('REQUEST_CONFIRMATION')).toBe(decisionLabel('ANNOTATE'));
    });

    it('says what the action actually does, which is nothing but write text', () => {
        expect(decisionLabel('ANNOTATE')).toBe('Notes only');
    });
});

describe('which outcome is marked out', () => {
    it('leaves the ordinary outcome quiet', () => {
        // Nine settled rows with a badge on every one of them mark out nothing.
        expect(transferStatusTone('SENT')).toBe('settled');
        expect(alertStateTone('OK')).toBe('settled');
    });

    it('marks money that did not move, which is the row that looked like the others', () => {
        // DECLINED sat in the same 12px grey as the eight SENT rows around it on the desktop.
        expect(transferStatusTone('DECLINED')).toBe('blocked');
        expect(alertStateTone('SUSPICIOUS')).toBe('blocked');
    });

    it('marks a payment that is stalled rather than finished', () => {
        for (const status of ['CREATED', 'HELD_FOR_REVIEW', 'WAITING_AUTH']) {
            expect(transferStatusTone(status)).toBe('pending');
        }
    });

    it('marks an alert nobody has picked up as open rather than as stalled', () => {
        // Not `pending`, which is the amber a held payment takes: work waiting in a queue is the
        // queue doing its job, and the two states are read by different people for different
        // reasons even though both are unfinished.
        expect(alertStateTone('NEW')).toBe('open');
    });

    it('gives every enum value exactly one of the four treatments', () => {
        const tones = ['settled', 'open', 'pending', 'blocked'];
        for (const status of TRANSFER_STATUSES) {
            expect(tones).toContain(transferStatusTone(status));
        }
        for (const state of ALERT_STATES) {
            expect(tones).toContain(alertStateTone(state));
        }
    });
});

describe('why a payment was declined', () => {
    it.each([
        ['OTP failed', 'one-time password'],
        ['Too many attempts', 'security reasons'],
        ['Authorization window expired', 'expired'],
        ['Insufficient funds on account', 'Insufficient balance'],
        ['Canceled by customer', 'canceled by the customer'],
    ])('turns the server sentence %s into one for a person', (reason, expected) => {
        expect(describeDeclineReason(reason)).toContain(expected);
    });

    it('matches whatever case the reason was written in', () => {
        // Some of these are typed by an analyst, so nothing about the capitals is guaranteed.
        expect(describeDeclineReason('CANCELED BY CUSTOMER')).toBe(
            describeDeclineReason('canceled by customer'),
        );
    });

    it('shows an unrecognised reason exactly as it was written', () => {
        // An analyst's own sentence says more than a general apology would, so it is not
        // replaced by one.
        const written = 'Beneficiary account is on a sanctions list.';
        expect(describeDeclineReason(written)).toBe(written);
    });

    it.each([null, undefined, ''])('renders nothing for %s', (missing) => {
        expect(describeDeclineReason(missing)).toBe('');
    });
});

/**
 * The two groups below are about the history table rather than about a server enum, and they are
 * here because what they check is still words. The heading over that table is in glossary.ts, for
 * the reason every sentence in that file is there: one role reads it on two platforms. The field
 * list is in fields.ts, which names what a row says and what each part of it is called. Neither
 * has a test file of its own and this run does not open one.
 */

describe('what the history beside an alert claims to be', () => {
    it('says out loud that it holds more than the account the alert names', () => {
        // The two desks headed one table with two sentences and both described a single account,
        // while the table now holds the customer's payments across every account they hold. A
        // heading that still narrowed it would leave the marked row in the column below marked
        // for no stated reason.
        expect(CUSTOMER_HISTORY_TITLE.toLowerCase()).toContain('all accounts');
        expect(CUSTOMER_HISTORY_TITLE.toLowerCase()).not.toContain('this account');
    });

    it('admits that it stops at ten', () => {
        // Nothing pages this table and no wider view opens from it, so a reader who is not told
        // the limit reads ten rows as the whole of what the customer has ever done.
        expect(CUSTOMER_HISTORY_TITLE).toMatch(/\b(10|ten)\b/i);
    });
});

describe('what one row of payment history says', () => {
    it('names where the money left as well as where it went', () => {
        expect(HISTORY_FIELDS).toContain('route');
        // The two account numbers are printed one above the other with no per-row wording, so the
        // heading is the only place the order of them is stated.
        expect(FIELD_LABEL.route).toContain(' / ');
        expect(FIELD_LABEL.route.indexOf('From')).toBeLessThan(FIELD_LABEL.route.indexOf('To'));
    });

    it('has no separate name for the beneficiary left behind', () => {
        // Replaced rather than joined: both web desks build the header and the row by walking
        // this list, so a surviving second key would have given them a seventh column with
        // nobody writing one.
        expect(HISTORY_FIELDS).not.toContain('toIban');
        expect(Object.keys(FIELD_LABEL)).not.toContain('toIban');
    });

    it('is still six facts, which is how many columns the desks draw', () => {
        expect(HISTORY_FIELDS).toHaveLength(6);
        expect(new Set(HISTORY_FIELDS).size).toBe(HISTORY_FIELDS.length);
    });

    it('gives every one of them a heading, and no two the same heading', () => {
        const headings = HISTORY_FIELDS.map((field) => FIELD_LABEL[field]);
        for (const heading of headings) {
            expect(heading.length).toBeGreaterThan(0);
        }
        expect(new Set(headings).size).toBe(headings.length);
    });

    it('requires the account the money left rather than tolerating a row without one', () => {
        // All three screens print this number with no branch for its absence, on the server's
        // promise that a row whose source account has no number is refused rather than sent. This
        // is the client half of that promise, and the build is what checks it: vitest strips the
        // types, so the failure this guards against is a compile error and not a red assertion.
        // @ts-expect-error a row that cannot say which account it left is not a history row
        const withoutSource: HistoryItem = {
            id: 1,
            createdAt: '2026-08-13T18:52:20Z',
            amount: { amount: '1000.00', currency: 'CZK' },
            status: 'SENT',
            toIban: 'CZ4308000000192000145407',
            declineReason: null,
        };

        expect(withoutSource.toIban).not.toBe('');
    });
});
