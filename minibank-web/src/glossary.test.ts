import { describe, it, expect } from 'vitest';
import {
    ALERTS_QUEUE_TITLE,
    ALERT_DETAILS_TITLE,
    ALERT_DETAIL_LOADING,
    DECISION_HINT,
    DECISION_NOTES_LABEL,
    DECISION_NOTES_PLACEHOLDER,
    DECISION_REASON_LABEL,
    DECISION_REASON_PLACEHOLDER,
    DECISION_TITLE,
    QUEUE_COUNTERS_BASIS,
    QUEUE_LOADING,
    REFRESH,
    REFRESH_BUSY,
    SELECT_ALERT,
    SELECT_ALERT_TO_DECIDE,
    SHOW_WITHDRAWN_ALERTS,
    alertStateLabel,
    alertStateTone,
    authMethodLabel,
    bankBoundaryLabel,
    bankBoundaryMark,
    CUSTOMER_HISTORY_TITLE,
    decisionLabel,
    describeDeclineReason,
    emptyQueueNote,
    hiddenAlertsNote,
    queueCounterCells,
    queueCounterTotal,
    queueCountersSentence,
    roleLabel,
    transferStatusLabel,
    transferStatusTone,
} from '@shared/glossary';
import {
    FIELD_LABEL,
    HISTORY_COLUMN_FIELDS,
    HISTORY_FEE_FIELD,
    HISTORY_FIELDS,
    HISTORY_MESSAGE_FIELD,
    HISTORY_NON_COLUMN_FIELDS,
    HISTORY_NOTE_FIELD,
    HISTORY_SETTLED_FIELD,
    HISTORY_UNDER_ROW_FIELDS,
} from '@shared/fields';
import {
    SIGNED_OUT_NOTICE,
    SIGN_IN_AS_SOMEONE_ELSE,
    noScreensNote,
} from '@shared/navigation';
import { SHOW_MORE_BUSY } from '@shared/paging';
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

    it('no longer states where the table stops, because the table now says so itself', () => {
        // This pinned "(last 10)" while nothing paged the table: a reader not told the limit read
        // ten rows as the whole of what the customer had ever done. The table pages now and prints
        // its own count underneath, so after one press of Show more the heading contradicted the
        // line below it. Where a list stops is what the count line says, on every list in both
        // applications, and a heading that answers it too is a second answer that goes stale the
        // moment the first one changes.
        expect(CUSTOMER_HISTORY_TITLE).not.toMatch(/\b(10|ten)\b/i);
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

    it('is nine facts, each named once', () => {
        // Nine facts and five columns: four are drawn somewhere other than a column, two because
        // they are prose and two because each belongs to the cell above it. Which four, and why,
        // is the group below. The count is asserted rather than derived so that a field arriving
        // on the wire has to be placed here before it can be read anywhere.
        expect(HISTORY_FIELDS).toHaveLength(9);
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
 * How the seven facts are split between the shapes a history table draws.
 *
 * The workstation had already worked this out and the customer application had not: it ran the
 * whole field list out as columns and paid for the sixth in the route cell, which is the tightest
 * one it has. One form for both, declared in fields.ts rather than decided again in each screen,
 * because a split that both desks make and neither states is how the two of them came apart the
 * first time.
 *
 * Two facts take no column now, and for reasons that do not resemble each other. Prose has no
 * width to be given. The fee has one and gives it up anyway, because it is the second line of the
 * amount it was added to rather than a second answer about the same payment, and a reader given
 * two money columns would have to add them to learn what left the account.
 */
describe('the history facts that take no column', () => {
    it('leaves the decline reason out of the header, because it is prose', () => {
        // A sentence somebody wrote has no width to be given, and sixty pixels is not a width for
        // one.
        expect(HISTORY_NOTE_FIELD).toBe('declineReason');
        expect(HISTORY_COLUMN_FIELDS).not.toContain(HISTORY_NOTE_FIELD);
    });

    it('keeps the decline reason out of the header and not out of the row', () => {
        // It is still a fact of the payment and still needs its word: the row under the payment
        // prints the label in front of the sentence, because nothing above it says what it is.
        expect(HISTORY_FIELDS).toContain(HISTORY_NOTE_FIELD);
        expect(FIELD_LABEL[HISTORY_NOTE_FIELD].length).toBeGreaterThan(0);
    });

    it('the fee is a fact of the payment and the second line of the amount', () => {
        // A field, because what the bank charged is true or absent independently of what was
        // sent, and a row that carried only the amount could not answer what the payment cost.
        expect(HISTORY_FIELDS).toContain(HISTORY_FEE_FIELD);
        expect(HISTORY_FEE_FIELD).toBe('fee');

        // Not a column, because it is read under the amount it was added to. Named out loud in
        // the set above rather than filtered out in each of the three tables, which is what the
        // group's own comment is about.
        expect(HISTORY_NON_COLUMN_FIELDS).toContain(HISTORY_FEE_FIELD);
        expect(HISTORY_COLUMN_FIELDS).not.toContain(HISTORY_FEE_FIELD);

        // It still needs its word, and needs it more than a field with a heading does: the line
        // is read out on the row itself, to somebody who hears a second money value under the
        // first with nothing above either to say which is which.
        expect(FIELD_LABEL[HISTORY_FEE_FIELD].length).toBeGreaterThan(0);
    });

    it('does not take the heading of the amount it stands under', () => {
        // The one key in this table that must not be renamed to describe the pair. It heads the
        // amount column of the alert queue on both platforms, labels the min and max filter on
        // the workstation, and stands over the large figure on the alert card, and all four of
        // those are the payment's own amount with no fee in them. Renaming it compiles, passes,
        // and lies in four places at once, so the literal is pinned here rather than derived.
        expect(FIELD_LABEL.amount).toBe('Amount');
        expect(FIELD_LABEL[HISTORY_FEE_FIELD]).not.toBe(FIELD_LABEL.amount);
    });

    it('leaves every other fact a column, in the order the fields are read', () => {
        // Derived by subtracting the named set rather than kept by hand, which is a weaker
        // promise than this test used to make: a field added to the union is a column only if
        // nobody named it above. What did not weaken is that leaving one out has to be written
        // down in one place for both platforms instead of happening in a table by omission.
        expect(HISTORY_COLUMN_FIELDS).toEqual(
            HISTORY_FIELDS.filter((f) => !HISTORY_NON_COLUMN_FIELDS.includes(f)),
        );
        expect(HISTORY_COLUMN_FIELDS).toHaveLength(
            HISTORY_FIELDS.length - HISTORY_NON_COLUMN_FIELDS.length,
        );
    });

    it('loses nothing between the field list and the shapes it is split into', () => {
        expect([...HISTORY_COLUMN_FIELDS, ...HISTORY_NON_COLUMN_FIELDS].sort()).toEqual(
            [...HISTORY_FIELDS].sort(),
        );
        // Split and not overlapping: a field in both sets would be drawn twice, once as a column
        // and once under the row, and both tests above would still pass.
        expect(
            HISTORY_COLUMN_FIELDS.filter((f) => HISTORY_NON_COLUMN_FIELDS.includes(f)),
        ).toEqual([]);
    });

    it('frees the column the route stack is short of, and only that one', () => {
        // The point of the move, stated as the number it changed: five headings where the customer
        // application drew six, and the space goes to the cell holding two account numbers. The
        // field list has grown by two since and this number has not, which is what it is for: both
        // additions were placed, one under the row and one inside a cell already there.
        expect(HISTORY_COLUMN_FIELDS).toHaveLength(5);
        expect(HISTORY_COLUMN_FIELDS).toContain('route');
    });

    it('puts the payer\'s message under the row and not in it, because it is prose too', () => {
        // The second sentence a row can carry, and it arrived after the decline reason had already
        // settled the shape. Same rule and therefore the same place: what somebody typed has no
        // width to be given, and a column of free text is a column of clipped free text.
        expect(HISTORY_MESSAGE_FIELD).toBe('message');
        expect(HISTORY_UNDER_ROW_FIELDS).toContain(HISTORY_MESSAGE_FIELD);
        expect(HISTORY_COLUMN_FIELDS).not.toContain(HISTORY_MESSAGE_FIELD);
        expect(FIELD_LABEL[HISTORY_MESSAGE_FIELD].length).toBeGreaterThan(0);
    });

    it('reads the two sentences in the order they were written', () => {
        // The payer's words, then the bank's. Both tables walk this list to draw the rows under a
        // payment, so the order is the order on screen and is decided once rather than per table.
        expect(HISTORY_UNDER_ROW_FIELDS).toEqual([HISTORY_MESSAGE_FIELD, HISTORY_NOTE_FIELD]);
    });

    it('keeps the settlement in the cell of the moment it is read against', () => {
        // Two timestamps and two questions: when it was asked for, and when the money moved. A
        // column of its own would have put them at two ends of the row, which is where a reader
        // cannot compare them; it is the second line of the created cell instead, the way the fee
        // is the second line of the amount.
        expect(HISTORY_SETTLED_FIELD).toBe('settledAt');
        expect(HISTORY_NON_COLUMN_FIELDS).toContain(HISTORY_SETTLED_FIELD);
        expect(HISTORY_COLUMN_FIELDS).not.toContain(HISTORY_SETTLED_FIELD);
        // It has no heading over it, so the word it is announced by is the only one there is.
        expect(FIELD_LABEL[HISTORY_SETTLED_FIELD].length).toBeGreaterThan(0);
        expect(FIELD_LABEL[HISTORY_SETTLED_FIELD]).not.toBe(FIELD_LABEL.createdAt);
    });
});

/**
 * The words two desks say for one thing, which is the whole reason this file exists.
 *
 * Each of these was typed twice, once per platform, and the two copies had drifted: a checkbox
 * with two names on one screen, a queue that said `Refreshing…` on one side and `Loading…` on the
 * other, a panel inviting the reader to look `on the left` at a layout with no left. They are one
 * constant each now, and what is checked here is not the wording but the properties the two desks
 * were disagreeing about.
 */
describe('one word for one thing across the two desks', () => {
    it('names the checkbox in the sentence that points at it', () => {
        // The note has to name the control by the label the control actually carries, or a reader
        // told that alerts are hidden is left hunting for what to press. One platform named a row
        // label only it had.
        expect(hiddenAlertsNote(3)).toContain(SHOW_WITHDRAWN_ALERTS);
        expect(hiddenAlertsNote(1)).toContain(SHOW_WITHDRAWN_ALERTS);
    });

    it('says Declined in the reader\'s word and not in the wire\'s', () => {
        // The sentence explains rows that read `Declined`, so it has to use that word. It used to
        // print the wire value in capitals, which is a fact about the server.
        expect(hiddenAlertsNote(2)).toContain(transferStatusLabel('DECLINED', 'analyst'));
        expect(hiddenAlertsNote(2)).not.toContain('DECLINED');
    });

    it('counts one alert in the singular', () => {
        expect(hiddenAlertsNote(1)).toContain('1 alert is');
        expect(hiddenAlertsNote(2)).toContain('2 alerts are');
    });

    it('tells an empty queue apart from a queue narrowed by its filters', () => {
        // A bare "No alerts" under a strip counting two is a contradiction until the sentence says
        // which alerts it means. The desk opens filtered, so the filtered branch is the ordinary
        // case rather than the exception.
        expect(emptyQueueNote({})).not.toBe(emptyQueueNote({ state: 'NEW' }));
        expect(emptyQueueNote({ excludeTransferStatus: ['DECLINED'] })).toBe(
            emptyQueueNote({ state: 'NEW' }),
        );
    });

    it('says what the counters strip counts, which is not the list under it', () => {
        const counters = { newCount: 1, suspiciousCount: 0, okCount: 1 };
        const sentence = queueCountersSentence(counters);

        // The numbers are taken before any filter and before any page, so a strip that does not
        // say so reads as a miscount of the rows below it.
        expect(sentence).toContain(QUEUE_COUNTERS_BASIS);
        expect(queueCounterTotal(counters)).toBe(2);
        expect(sentence).toContain('2');

        // The three words are the queue's own, not three literals typed into a sentence: this
        // desk used to say `confirmed fraud` and `cleared` beside rows saying `Confirmed fraud`
        // and `Cleared`.
        for (const cell of queueCounterCells(counters)) {
            expect(cell.label).toBe(alertStateLabel(cell.state));
            expect(sentence).toContain(cell.label);
        }
    });

    it('gives the queue and the panel beside it one waiting word each', () => {
        // `Loading detail…` sat next to a queue that was also capable of loading, so a reader who
        // glanced at it learned only that something was.
        expect(QUEUE_LOADING).not.toBe(ALERT_DETAIL_LOADING);
        expect(readable(QUEUE_LOADING)).toBe(true);
        expect(readable(ALERT_DETAIL_LOADING)).toBe(true);
    });

    it('waits in one word wherever a list is being re-read', () => {
        // `Refreshing…` against `Loading…` for the same request. One wait, one word, and it is the
        // same one the Show more control already says.
        expect(REFRESH_BUSY).toBe(SHOW_MORE_BUSY);
        expect(REFRESH_BUSY).not.toBe(REFRESH);
    });

    it('invites the reader to the queue rather than to a position on the screen', () => {
        // `Select an alert on the left` describes furniture, is true of one of the two layouts,
        // and stops being true at the width where the panels stack.
        expect(SELECT_ALERT.toLowerCase()).not.toContain('left');
        // Two panels can be empty at once, and a reader looking at both must not read one
        // sentence twice: one asks for an alert to show, the other for an alert to act on.
        expect(SELECT_ALERT).not.toBe(SELECT_ALERT_TO_DECIDE);
    });

    it('titles the three panels by name and in sentence case', () => {
        // One platform titled the middle panel `Alert Detail: Review Suspicious Transaction`,
        // which tells an analyst what a fraud desk is for on every alert they read.
        for (const title of [ALERTS_QUEUE_TITLE, ALERT_DETAILS_TITLE, DECISION_TITLE]) {
            expect(title).toBe(title.charAt(0) + title.slice(1).toLowerCase());
            expect(title).not.toContain(':');
        }
        expect(new Set([ALERTS_QUEUE_TITLE, ALERT_DETAILS_TITLE, DECISION_TITLE]).size).toBe(3);
    });

    it('captions the two decision boxes by who reads what is typed in them', () => {
        // `Notes` over a hint reading `internal notes` says the word twice and neither time says
        // what the box is for. The difference between these two boxes is their audience: the
        // reason rides with the decision and reaches the customer on a refusal.
        expect(DECISION_REASON_LABEL).not.toBe(DECISION_NOTES_LABEL);
        expect(DECISION_NOTES_PLACEHOLDER).not.toBe(DECISION_NOTES_LABEL);
        expect(DECISION_REASON_PLACEHOLDER).not.toBe(DECISION_REASON_LABEL);
    });

    it('tells the analyst what the emptied notes box will do', () => {
        // True of both desks and printed by one. Whatever is left in the box is what any of the
        // three buttons saves, an emptied box included, and that is the sentence an analyst gets
        // wrong once.
        expect(DECISION_HINT).toContain('notes');
        expect(DECISION_HINT.split('. ').length).toBeGreaterThanOrEqual(3);
    });

    it('says the sign-out notice and the way back in once', () => {
        // Typed into both shells, identically, which is the state a word is in just before it
        // drifts. It names no cause, because four different endings answer the same 401.
        expect(SIGNED_OUT_NOTICE).not.toMatch(/expire/i);
        expect(SIGN_IN_AS_SOMEONE_ELSE).not.toMatch(/log ?out/i);
        expect(noScreensNote('alice')).toContain('alice');
    });
});
