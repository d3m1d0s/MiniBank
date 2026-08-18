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
 * Three and no more, because a table of nine settled rows with a badge on every one of them
 * marks out nothing. `settled` carries no treatment at all beyond the body colour.
 */
export type Tone = 'settled' | 'blocked' | 'pending';

const TRANSFER_STATUS: Record<string, Record<Audience, string>> = {
    CREATED: { customer: 'Created', analyst: 'Created' },
    HELD_FOR_REVIEW: { customer: 'Under review', analyst: 'Under review' },
    WAITING_AUTH: {
        customer: 'Waiting for your code',
        analyst: "Waiting for the customer's code",
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
    NEW: 'pending',
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
