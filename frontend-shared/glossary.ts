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
 * The three things an analyst can press, as a closed set.
 *
 * The same three tokens as FraudDecision in fraud.ts, written out again rather than imported:
 * that module carries the calls to the server, and a word list must not pull the HTTP layer in
 * behind it. Each desk passes its own value and the two unions meet by structure.
 */
export type DecisionAction = 'APPROVE' | 'DECLINE' | 'ANNOTATE';

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
const DECISION_ACTION: Record<DecisionAction, string> = {
    APPROVE: 'Approve: release to the customer',
    DECLINE: 'Decline: record confirmed fraud',
    ANNOTATE: 'Save notes, no decision',
};

/** What one of the three decision buttons says. Identical on both desks, which is the point. */
export function decisionActionLabel(action: DecisionAction): string {
    return DECISION_ACTION[action];
}

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
    action: DecisionAction,
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

    return 'Notes, assignee and tags saved. No decision was taken: the alert is still open and the transfer is unchanged.';
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
