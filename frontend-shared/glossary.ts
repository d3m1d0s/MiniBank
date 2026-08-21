/**
 * The words for the values the server owns, in one place, for both applications.
 *
 * Every closed set below is a Java enum. Their names are written for the code that switches on
 * them, and they were reaching people unchanged: `HELD_FOR_REVIEW` under a Confirm button a
 * customer could not press, `SUSPICIOUS` as an analyst's own recorded verdict, `FRAUD_ANALYST`
 * beside a name in a header band. One screen had already translated two of them, into
 * `Under review` and `Waiting for your code`, which is the wording every table here is built out
 * from rather than a second one invented beside it.
 *
 * A value nobody here knows is printed as itself. An enum the server adds tomorrow must appear on
 * the screen looking foreign, which is a bug report; blanking it would make the row look empty and
 * the addition invisible.
 *
 * The tone functions are the other half of the same job. A word alone does not distinguish a
 * payment that went from one that was refused when both are set in the same grey, so each closed
 * set also says which of three treatments its value takes. The three names are the vocabulary;
 * the colours behind them belong to each skin and are not decided here.
 *
 * The decision buttons and the sentence that follows a decision are here for the same reason as
 * the labels. They are not a value the server sends, they are what one role reads while doing one
 * job, and each desk had written its own: two labels for the same press and two sentences for the
 * same outcome. What a screen looks like is the skin's business; what it says is not.
 */

/**
 * The three things an analyst can press, which are the three tokens the wire takes.
 *
 * Imported rather than declared a second time. The set used to be written out here as well, on
 * the argument that a word list must not pull the HTTP layer in behind it, and that argument was
 * about the wrong thing: `import type` is erased, so this module still reaches no server and is
 * still testable without one, while a fourth decision can no longer arrive on the wire and leave
 * the buttons and the outcome sentences behind. Two declarations of one closed set do not fail a
 * build when they part company; they answer the analyst with the raw token.
 */
import type { FraudDecision } from './fraud';

/**
 * Who is reading.
 *
 * One status genuinely needs two sentences. `WAITING_AUTH` is `Waiting for your code` to the
 * customer whose code it is and a lie to the analyst reading the same row, so the caller says
 * which screen it is on. There is deliberately no default: a default is how the analyst desk
 * would end up telling an analyst to check their phone.
 */
export type Audience = 'customer' | 'analyst';

/**
 * How a value is marked out: the ordinary outcome quiet, the exceptional one visible.
 *
 * Four, and the restraint is in `settled` rather than in the count: it carries no colour and no
 * weight at all, so a table of nine finished rows marks out nothing and the eye lands only on the
 * rows that are still somebody's problem.
 *
 * `open` is separated from `pending` because the two ask different things of the reader. An open
 * alert is work waiting to be picked up, which is a good state and reads green; a payment held for
 * review or waiting for a code is stalled and reads amber. Both are bold, because both are
 * unfinished. `blocked` is the refused end of the story and reads red.
 */
export type Tone = 'settled' | 'open' | 'pending' | 'blocked';

const TRANSFER_STATUS: Record<string, Record<Audience, string>> = {
    CREATED: { customer: 'Created', analyst: 'Created' },
    HELD_FOR_REVIEW: { customer: 'Under review', analyst: 'Under review' },
    WAITING_AUTH: {
        customer: 'Waiting for your code',
        analyst: 'Awaiting customer code',
    },
    SENT: { customer: 'Sent', analyst: 'Sent' },
    DECLINED: { customer: 'Declined', analyst: 'Declined' },
};

const TRANSFER_STATUS_TONE: Record<string, Tone> = {
    CREATED: 'pending',
    HELD_FOR_REVIEW: 'pending',
    WAITING_AUTH: 'pending',
    SENT: 'settled',
    DECLINED: 'blocked',
};

/**
 * The alert's own lifecycle, which is the analyst's verdict and never the customer's business.
 *
 * `OK` and `SUSPICIOUS` are not opinions about the payment, they are what an analyst decided:
 * approving writes OK, declining writes SUSPICIOUS. `Suspicious` as a word understates it, and
 * the button that writes it already says what it means, so the word here matches the button.
 */
const ALERT_STATE: Record<string, string> = {
    NEW: 'New',
    OK: 'Cleared',
    SUSPICIOUS: 'Confirmed fraud',
};

const ALERT_STATE_TONE: Record<string, Tone> = {
    // Open work rather than a stalled payment, so green rather than amber: an alert nobody has
    // looked at yet is the queue doing its job, and the analyst's eye should go to it.
    NEW: 'open',
    OK: 'settled',
    SUSPICIOUS: 'blocked',
};

/**
 * The three things an analyst can record.
 *
 * `REQUEST_CONFIRMATION` is here beside `ANNOTATE` and reads the same, because it is the same
 * decision under its old name. The token was renamed for describing something it never did, but
 * rows written before the rename keep the old spelling in the database forever, and an alert
 * decided last month must not stop having a verdict because the word for it changed.
 */
const DECISION: Record<string, string> = {
    APPROVE: 'Approved',
    DECLINE: 'Declined',
    ANNOTATE: 'Notes only',
    REQUEST_CONFIRMATION: 'Notes only',
};

const ROLE: Record<string, string> = {
    CUSTOMER: 'Customer',
    FRAUD_ANALYST: 'Fraud analyst',
    OPERATIONS: 'Operations',
    MANAGEMENT: 'Management',
};

/** How a payment was authorized. Sent as the method identifier of the domain's Payment. */
const AUTH_METHOD: Record<string, string> = {
    OTP: 'One time code',
    CARD: 'Card',
};

/**
 * Where a payment that has already left the account stands with the bank on the other side.
 *
 * Two values on the wire and a null, and the null is not a third value: it covers a payment
 * credited inside this bank, a payment that has not settled, and every row written before the
 * column existed. Which side of the bank the money was going is answered by the destination
 * account and by nothing else; see {@link bankBoundaryLabel}.
 */
const DISPATCH_STATE: Record<string, string> = {
    PENDING: 'Waiting in the outgoing queue',
    DISPATCHED: 'Handed to the other bank',
};

/**
 * The sentence for a transfer status, for the person in front of this screen.
 *
 * @param audience which application is asking; see {@link Audience} for why it is required
 */
export function transferStatusLabel(
    status: string | null | undefined,
    audience: Audience,
): string {
    if (!status) {
        return '';
    }
    return TRANSFER_STATUS[status]?.[audience] ?? status;
}

/** The sentence for a fraud alert state. Analyst vocabulary; no customer screen shows one. */
export function alertStateLabel(state: string | null | undefined): string {
    return label(ALERT_STATE, state);
}

/** The sentence for an analyst's recorded decision, or the empty string when none was taken. */
export function decisionLabel(decision: string | null | undefined): string {
    return label(DECISION, decision);
}

/** The sentence for an application role. */
export function roleLabel(role: string | null | undefined): string {
    return label(ROLE, role);
}

/** The sentence for the way a payment was authorized. */
export function authMethodLabel(method: string | null | undefined): string {
    return label(AUTH_METHOD, method);
}

/**
 * What is left to happen to a payment that has left the account, or the empty string when there
 * is nothing left to say.
 *
 * The empty string is the point of the function rather than a gap in it. A payment credited
 * inside this bank has no onward leg, and neither has one that has not settled, so a screen that
 * read the absence as "the money stayed here" would state as a fact something this field cannot
 * know. What answers that is the destination account, in {@link bankBoundaryLabel}.
 */
export function dispatchStateLabel(state: string | null | undefined): string {
    return label(DISPATCH_STATE, state);
}

/**
 * What a quote says about the one time code, before the payment is sent.
 *
 * Spoken only when there will be one, by the same restraint that leaves an ordinary destination
 * unmarked: a customer who will simply be charged has nothing to be told, and a line that appears
 * on every payment has stopped being read by the time it matters.
 *
 * It is the bank's real rule and not the screen's guess at it: the quote is priced through the
 * same risk rules the payment itself runs, so the same amount answers differently to a saved payee
 * the bank trusts than to an account number typed into the box. It says nothing about whether the
 * payment would be held for review, and must not be made to: that would tell whoever holds the
 * customer's credentials where the threshold lies.
 */
export function authorizationNote(required: boolean): string {
    return required ? 'This payment will ask for a one time code.' : '';
}

/**
 * Which of the three treatments a transfer status takes.
 *
 * An unknown status is `pending`, not `settled`. A state the server has just learned to send is
 * by definition not one of the two this client knows to be finished, and marking it visibly is
 * how it gets noticed instead of joining the quiet rows.
 */
export function transferStatusTone(status: string | null | undefined): Tone {
    return status ? (TRANSFER_STATUS_TONE[status] ?? 'pending') : 'pending';
}

/** Which of the three treatments a fraud alert state takes. Same rule for an unknown one. */
export function alertStateTone(state: string | null | undefined): Tone {
    return state ? (ALERT_STATE_TONE[state] ?? 'pending') : 'pending';
}

function label(table: Record<string, string>, value: string | null | undefined): string {
    if (!value) {
        return '';
    }
    return table[value] ?? value;
}

/**
 * What the three buttons say.
 *
 * They said different things: `Approve: release to customer` against `Approve: release to the
 * customer`, and `Decline: record fraud` against `Decline: record confirmed fraud`. One analyst
 * doing one job on two platforms was reading two labels for the same press, which is the kind of
 * difference nobody notices and everybody has to hold in their head.
 *
 * The surviving wording is the longer one in both cases, and not because it is longer. Declining
 * writes the alert state SUSPICIOUS, which this file already calls `Confirmed fraud` and says so
 * deliberately; a button whose label drops the word records something the panel above it then
 * names differently. And the payment goes back to the customer to confirm, so `to the customer`
 * is the phrase the sentence under the button already uses.
 */
const DECISION_ACTION: Record<FraudDecision, string> = {
    APPROVE: 'Approve: release to the customer',
    DECLINE: 'Decline: record confirmed fraud',
    ANNOTATE: 'Save notes, no decision',
};

/** What one of the three decision buttons says. Identical on both desks, which is the point. */
export function decisionActionLabel(action: FraudDecision): string {
    return DECISION_ACTION[action];
}

/**
 * What the box above those three buttons is called.
 *
 * One caption, because the two desks had two: one said "Reason / note for this decision" and the
 * other said "Reason" over a box hinting "optional reason". Neither names a verdict and neither
 * may, which is the part worth stating out loud: the comment goes on the wire whichever of the
 * three is pressed, so a caption reading "reason for declining" would tell an analyst clearing an
 * alert that what they wrote is for a refusal they are not making.
 */
export const DECISION_REASON_LABEL = 'Reason for this decision';

/**
 * The words for holding an alert: the two controls, the alert nobody holds, and the two positions
 * of the filter that reads the queue by it.
 *
 * One vocabulary because there is one behaviour behind it. Assignment is a control and a filter
 * and not a field: the only name that can be written is the analyst's own, so there is nobody to
 * type and no colleague to hand an alert to, and the queue is read either as mine or as all of it.
 *
 * `unassigned` is a word and not the table's dash, and only in the panel that lists facts one to a
 * line. Both desks had already reached for it there, where a dash reads as a value withheld; the
 * queue column keeps the dash, which is what every other unfilled cell in it shows.
 */
export const TAKE_ALERT = 'Take this alert';
export const RELEASE_ALERT = 'Release';
export const UNASSIGNED = 'unassigned';
export const ASSIGNED_TO_ME = 'Mine';
export const ASSIGNED_TO_ANYONE = 'All';

/**
 * What the history beside an alert is a history OF.
 *
 * The two desks headed one table with two sentences and neither was true: one said "last 10
 * transfers from this account" and one said "Customer & Transfer history", while the table held
 * the payments of one account out of the customer's two. It now holds the customer's payments
 * across every account they hold, and the heading has to say so, or the account marked in the
 * column below is marked for no stated reason.
 */
export const CUSTOMER_HISTORY_TITLE = 'Customer history, all accounts (last 10)';

/**
 * Where the account a payment names is held: this bank, or one on the far side of the network.
 *
 * The words are about the ACCOUNT and not about the money, and that is the first thing that
 * decides them. `Left the bank` would be a lie on a payment still held for review, and payments
 * still held for review are most of what a fraud desk looks at. What is true of every row,
 * decided or not, is where the number on it lives.
 *
 * COLOUR IS NOT AVAILABLE, and that decides the rest. Four tones already run down these rows and
 * they answer a different question, whether the row is still somebody's problem; a fifth colour
 * about a different axis would be read as a fifth state of the same one. The other channel in the
 * route cell is taken as well: weight and ink mark the account the alert was raised on. What is
 * left is a word, which needs no legend, no key and no colour, and survives being enlarged.
 *
 * Which is why only one of the two words is ever spoken on a row. This file's rule is that the
 * ordinary is quiet and the exceptional visible, the same restraint that leaves `settled` with no
 * colour at all.
 *
 * The word goes on the payment that STAYS, and the choice was made the wrong way round once and
 * looked it. Marking the money that leaves reads well as an argument, because a payment gone out
 * over the network cannot be pulled back, and it fails as a column: this bank's customers pay
 * outward almost always, so the word stood on every row of every table, and a mark that is never
 * absent marks nothing. It also cost the row a line it could not spare. The reading that is worth
 * a reader's attention is the rare one, and here that is a payment credited inside the bank in the
 * same unit of work as the debit.
 *
 * The polarity therefore belongs to this dataset rather than to the idea, which is worth knowing
 * before anyone flips it back. A bank whose customers mostly pay each other would want the other
 * word, and the rule to keep is not "say outbound" but "say whichever is the exception here".
 *
 * A panel that lists facts one to a line is not scanning anything and has no such column to
 * spoil, so there both readings are worth printing: that is the second function.
 */
const BANK_BOUNDARY: Record<'internal' | 'outbound', string> = {
    internal: 'This bank',
    outbound: 'Another bank',
};

/**
 * What a row says about the account it is paying, or the empty string when it says nothing.
 *
 * The empty string is the outbound case and it is the point of the function rather than a gap in
 * it: the rule that the ordinary destination goes unmarked is written once, here, instead of as
 * the same condition in each of the places that draw a route.
 *
 * @param toIbanInBank the field of that name off the wire, already derived there; see fraud.ts
 *                     for why the client is not handed the two halves to combine itself
 */
export function bankBoundaryMark(toIbanInBank: boolean): string {
    return toIbanInBank ? BANK_BOUNDARY.internal : '';
}

/** The same fact stated either way, for a panel that lists facts rather than one that scans rows. */
export function bankBoundaryLabel(toIbanInBank: boolean): string {
    return toIbanInBank ? BANK_BOUNDARY.internal : BANK_BOUNDARY.outbound;
}

/**
 * What a decision actually did, read off the payment the server sent back rather than off the
 * button that was pressed.
 *
 * Deriving it from the button was safe only while every decision did the one thing its label
 * said: a DECLINE on a payment that has already gone is now accepted and records the verdict
 * without stopping anything, and announcing "Transfer declined" for it would tell the analyst the
 * money was held when it is gone, a worse lie than the refusal it replaced.
 *
 * Both desks wrote these sentences themselves and two of them had drifted apart, so the same
 * outcome was announced as `Alert marked as confirmed fraud and the transfer is Declined.` on one
 * platform and `Alert recorded as confirmed fraud, and the transfer is declined.` on the other.
 * The lower case reading is the one kept: the status is being spoken inside a sentence here rather
 * than printed as a value, and it is the only place in either application where that is true.
 *
 * @param status the payment's status AFTER the decision, not before it
 */
export function describeDecision(
    action: FraudDecision,
    status: string | null | undefined,
): string {
    // The analyst's own vocabulary, in the same words the queue and the panel above it use. It
    // used to interpolate the enum: "the transfer is WAITING_AUTH".
    const said = transferStatusLabel(status, 'analyst').toLowerCase();

    if (action === 'APPROVE') {
        return status === 'WAITING_AUTH'
            ? 'Alert cleared. The payment is released to the customer to confirm; no money has moved.'
            : `Alert cleared. The transfer was already ${said}, so there was nothing to release.`;
    }

    if (action === 'DECLINE') {
        return status === 'SENT'
            ? 'Recorded as confirmed fraud. The payment had already been sent and has NOT been reversed.'
            : `Alert recorded as confirmed fraud, and the transfer is ${said}.`;
    }

    // The sentence used to promise two things this press cannot do. Tags cannot be written over
    // the decision route at all, and the assignee is written by a route of its own that no button
    // in this panel calls, so an analyst was told twice over that something had been saved which
    // had never been sent. What is left is what the press actually does.
    return 'Notes saved. No decision was taken: the alert is still open and the transfer is unchanged.';
}

/**
 * The sentence for why a payment was declined.
 *
 * Not a closed set, and that is the whole difficulty: the reason is free text written by whoever
 * declined the payment, an analyst at a keyboard or one of a handful of fixed sentences the
 * server writes itself. So it is matched loosely, and anything unrecognised is shown exactly as
 * it was written rather than replaced by a general apology that says less than the raw string did.
 *
 * It lives here rather than on the customer's screen because it stopped being one screen's
 * problem: the analyst reads these same strings in a payment's history on both desks, and the
 * customer is about to read them on a declined payment of their own.
 */
export function describeDeclineReason(reason: string | null | undefined): string {
    if (!reason) {
        return '';
    }

    const r = reason.toLowerCase();

    if (r.includes('otp failed') || r.includes('wrong otp')) {
        return 'Wrong one-time password (OTP). Please check the code and try again.';
    }
    if (r.includes('too many') || r.includes('attempts exceeded')) {
        return 'Too many incorrect OTP attempts, so this transfer was declined for security reasons.';
    }
    if (r.includes('expired') || r.includes('authorization window')) {
        return 'Authorization time window has expired. Please create a new transfer if you still want to send money.';
    }
    if (r.includes('insufficient funds')) {
        return 'Insufficient balance. Top up your account or cancel this transfer.';
    }
    if (
        r.includes('canceled by customer') ||
        r.includes('cancelled by customer') ||
        r.includes('canceled')
    ) {
        return 'The transfer was canceled by the customer.';
    }

    return reason;
}
