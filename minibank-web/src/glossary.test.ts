import { describe, it, expect } from 'vitest';
import {
    alertStateLabel,
    alertStateTone,
    authMethodLabel,
    bankBoundaryLabel,
    bankBoundaryMark,
    CUSTOMER_HISTORY_TITLE,
    decisionLabel,
    describeDeclineReason,
    roleLabel,
    transferStatusLabel,
    transferStatusTone,
} from '@shared/glossary';
import {
    FIELD_LABEL,
    HISTORY_COLUMN_FIELDS,
    HISTORY_FIELDS,
    HISTORY_NOTE_FIELD,
} from '@shared/fields';
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

/**
 * The second axis that runs down a history row, and the one that had no channel left.
 *
 * Whether the bank holds the account a payment names is not a state of the payment, so it cannot
 * borrow the four tones above: they answer whether the row is still somebody's problem, and a
 * fifth colour beside them would be read as a fifth answer to that question. Weight and ink in the
 * same cell are spoken for as well, by the account the alert was raised on. What is left is a
 * word, and these are the rules that word has to keep.
 */
describe('where the account a payment names is held', () => {
    it('says nothing on a row whose money left the bank', () => {
        // This file's own restraint, applied to a second axis: the ordinary is quiet. This bank's
        // customers pay outward almost always, so a word here would stand on every row of every
        // table, and a mark that is never absent marks nothing.
        expect(bankBoundaryMark(false)).toBe('');
    });

    it('marks the rare row where the money never left the bank', () => {
        // The exception in this dataset, and the reading worth a reader's attention: credited to
        // an account here in the same unit of work as the debit, with no gateway in it at all.
        expect(readable(bankBoundaryMark(true))).toBe(true);
    });

    it('states the fact either way where a panel lists facts one to a line', () => {
        // A definition list is not scanned, so it has no column shape to spoil, and there the
        // internal reading is worth printing rather than left as the absence of the other one.
        expect(readable(bankBoundaryLabel(true))).toBe(true);
        expect(readable(bankBoundaryLabel(false))).toBe(true);
        expect(bankBoundaryLabel(true)).not.toBe(bankBoundaryLabel(false));
    });

    it('spells the internal case one way, on a row and in a panel alike', () => {
        // An analyst moves between the queue and the panel beside it several times a case. Two
        // spellings of one fact would be read as two facts.
        expect(bankBoundaryMark(true)).toBe(bankBoundaryLabel(true));
    });

    it('names where an account is rather than what the money did', () => {
        // "Left the bank" would be a lie on a payment still held for review, and payments still
        // held for review are most of what a fraud desk looks at. What is true of every row,
        // decided or not, is which bank holds the number printed on it.
        expect(bankBoundaryLabel(true).toLowerCase()).toContain('bank');
        expect(bankBoundaryLabel(false).toLowerCase()).toContain('bank');
    });

    it('fits beside an account number in the tightest cell either application has', () => {
        // It sits on the beneficiary line of the route stack, under a source line, in a cell that
        // already holds twenty-nine characters of grouped IBAN. The literal is deliberately not
        // pinned here, only the room it has to fit into.
        expect(bankBoundaryLabel(true).length).toBeLessThanOrEqual(16);
        expect(bankBoundaryLabel(false).length).toBeLessThanOrEqual(16);
    });

    it('cannot be mistaken for a status word standing in the same row', () => {
        // Both axes are words on one line now that colour is spoken for. A word shared with the
        // status column would be read as an answer to the status column.
        const statuses = [
            ...TRANSFER_STATUSES.flatMap((s) => [
                transferStatusLabel(s, 'customer'),
                transferStatusLabel(s, 'analyst'),
            ]),
            ...ALERT_STATES.map((s) => alertStateLabel(s)),
        ];

        expect(statuses).not.toContain(bankBoundaryLabel(true));
        expect(statuses).not.toContain(bankBoundaryLabel(false));
    });

    it('has two readings, and the mark is one of them or nothing', () => {
        // Two and not three. A payment that has not settled is answered from the live account
        // store when the row is read, so "not decided yet" is not a state this pair has to carry,
        // and no screen has to draw a third thing.
        expect([bankBoundaryMark(true), bankBoundaryMark(false)]).toEqual([
            bankBoundaryLabel(true),
            '',
        ]);
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

    it('is still six facts, each named once', () => {
        // Six facts and no longer six columns: one of them is prose and is drawn under the row it
        // explains. Which one, and why, is the group below.
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

    it('is handed the answer about the beneficiary account, not the two halves of it', () => {
        // The server decides this from a settled payment's dispatch record or, for one that has
        // not settled, from the live account store. Sending the raw dispatch state instead would
        // put that rule in every desk that reads a row, in the same words, which is the
        // duplication this module and the glossary exist to end. Checked by the build for the
        // same reason as the row above: vitest strips the types.
        // @ts-expect-error a row that cannot say where the beneficiary account is held is not one
        const withoutBoundary: HistoryItem = {
            id: 1,
            createdAt: '2026-08-13T18:52:20Z',
            amount: { amount: '1000.00', currency: 'CZK' },
            status: 'SENT',
            fromIban: 'CZ6508000000192000145399',
            toIban: 'CZ4308000000192000145407',
            declineReason: null,
        };

        expect(withoutBoundary.toIban).not.toBe('');
    });
});

/**
 * How the six facts are split between the two shapes a history table draws.
 *
 * The workstation had already worked this out and the customer application had not: it ran the
 * whole field list out as columns and paid for the sixth in the route cell, which is the tightest
 * one it has. One form for both, declared in fields.ts rather than decided again in each screen,
 * because a split that both desks make and neither states is how the two of them came apart the
 * first time.
 */
describe('the one history fact that is prose', () => {
    it('is the decline reason, and it is not a column', () => {
        // A sentence somebody wrote has no width to be given, and sixty pixels is not a width for
        // one.
        expect(HISTORY_NOTE_FIELD).toBe('declineReason');
        expect(HISTORY_COLUMN_FIELDS).not.toContain(HISTORY_NOTE_FIELD);
    });

    it('is out of the header and not out of the row', () => {
        // It is still a fact of the payment and still needs its word: the row under the payment
        // prints the label in front of the sentence, because nothing above it says what it is.
        expect(HISTORY_FIELDS).toContain(HISTORY_NOTE_FIELD);
        expect(FIELD_LABEL[HISTORY_NOTE_FIELD].length).toBeGreaterThan(0);
    });

    it('leaves every other fact a column, in the order the fields are read', () => {
        // Derived from the field list rather than kept by hand. A seventh field would turn up as a
        // column on both platforms on the next build, where a hand kept list of five would leave
        // it out in silence.
        expect(HISTORY_COLUMN_FIELDS).toEqual(
            HISTORY_FIELDS.filter((f) => f !== HISTORY_NOTE_FIELD),
        );
        expect(HISTORY_COLUMN_FIELDS).toHaveLength(HISTORY_FIELDS.length - 1);
    });

    it('loses nothing between the field list and the two shapes', () => {
        expect([...HISTORY_COLUMN_FIELDS, HISTORY_NOTE_FIELD].sort()).toEqual(
            [...HISTORY_FIELDS].sort(),
        );
    });

    it('frees the column the route stack is short of, and only that one', () => {
        // The point of the move, stated as the number it changed: five headings where the customer
        // application drew six, and the space goes to the cell holding two account numbers.
        expect(HISTORY_COLUMN_FIELDS).toHaveLength(5);
        expect(HISTORY_COLUMN_FIELDS).toContain('route');
    });
});
