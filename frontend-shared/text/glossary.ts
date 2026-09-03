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
import type { AlertCounters, AlertFilters, FraudDecision } from '../wire/fraud';

/**
 * One word for one wait, across both applications and both panels of the desk.
 *
 * The queue's Refresh and the Show more at the foot of every list are the same promise to the
 * reader, that a request is out and the screen has not stopped. They said `Refreshing…` on one
 * platform and `Loading…` on the other, over lists that page with `Loading…` underneath, so the
 * one panel could be watched saying two words about one wait. The busy word is taken from the
 * paging module rather than written again here: two literals agreeing today is how they stop.
 */
import { SHOW_MORE_BUSY } from '../logic/paging';

/**
 * The clock the authorization window is read against, and the words a moment is printed in.
 *
 * The state machine stays in format.ts and only the sentences are written here, which is the same
 * division every other function in this file keeps: what is true of a value is decided once, and
 * what a person is told about it is decided once, and neither is decided twice. The alternative
 * was a screen that read the state itself and then chose between three literals of its own, which
 * is how the one line this replaces came to say a bare timestamp under a live Confirm button on a
 * window that had closed eight days earlier.
 */
import { NOT_RECORDED, authWindowState, formatDateTime, formatTimeLeft } from './format';

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
 * The same answer for a screen that has to print something, whether the field is a column or a
 * line of a detail panel.
 *
 * A payment waiting for its code has no auth method yet, and that is a fact about the payment
 * rather than a value the screen failed to fetch. {@link authMethodLabel} answers the empty string
 * there, which every call site then had to finish for itself, and they finished it differently:
 * the detail panels said `not recorded` while the queue column beside them fell back to the data
 * table's dash. The dash is the right default for a column of amounts or dates, where a gap reads
 * as a load that failed, and it is the wrong one here, where it reads as a method that exists and
 * is being withheld from a customer who is about to authorize the payment.
 *
 * So the choice is made once, for the field rather than for the shape it is drawn in, and no
 * screen decides it again.
 */
export function authMethodText(method: string | null | undefined): string {
    return authMethodLabel(method) || NOT_RECORDED;
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
 * What a payment waiting for a code says about the time it has, in one of exactly three
 * sentences.
 *
 * There were none. The screen printed the deadline as a value behind the label `Will be expired
 * after:`, a fixed timestamp that said nothing about whether the moment had passed, and a payment
 * whose window had closed a week earlier read the same as one with four minutes on it. A label and
 * a value are how a fact is stated; whether a control will still work is not a fact of that shape.
 *
 * Each sentence answers the question the state actually raises. Open: the moment it ends and how
 * much of it is left, so the customer can decide whether to fetch their phone. Closed: what has
 * been lost and the one way out, because Confirm is dead by then and Cancel is the only live
 * control on the row. None: that there is no limit at all, which is a real state and not a gap,
 * reached whenever an analyst releases a payment from review.
 *
 * NONE OF THE THREE MAY BE THE EMPTY VALUE. That mark is the table's way of saying a cell is
 * unknown, and it is what this line printed on a payment released from review: a sign meaning
 * "not known" against a rule the bank knows perfectly well.
 *
 * The moment inside the sentence keeps the numeric form every other timestamp in both
 * applications takes, from formatDateTime and never assembled here.
 */
export function authWindowNote(authValidUntil: string | null | undefined, now: Date): string {
    switch (authWindowState(authValidUntil, now)) {
        case 'open': {
            // The countdown is added only while there is one to read. It goes quiet past an hour,
            // and the deadline beside it is then the whole of what this sentence has to say.
            const left = formatTimeLeft(authValidUntil, now);
            const deadline = `The code can be entered until ${formatDateTime(authValidUntil)}`;
            return left ? `${deadline}, in ${left}.` : `${deadline}.`;
        }
        case 'closed':
            return (
                `The time to confirm this payment ran out at ${formatDateTime(authValidUntil)}, ` +
                'so the code can no longer be used. Cancel this transfer and create a new payment.'
            );
        default:
            // Both formatters are left unasked here, which is the point of taking the branch this
            // way round: there is no instant to print, so no call can reach the empty value with
            // a timestamp's meaning attached to it.
            return 'No deadline is set on this payment, so it waits for your code with no time limit.';
    }
}

/**
 * How many guesses are left, and at the last one what spending it costs.
 *
 * One function for the two places that say it, which used to be one place too few. The count was
 * printed on the screen as a neutral label and number, `Tries left: 1`, in the same muted grey as
 * every other hint; the warning existed only inside the message for a code that had ALREADY been
 * refused, so the sentence naming the consequence arrived one attempt after it would have been
 * worth reading.
 *
 * The zero branch is unreachable from the screen and kept for the error path: the third wrong code
 * declines the payment inside the same request, so a customer reading this line sees three, two or
 * one and never a payment with no attempts left still asking for a code.
 */
export function attemptsLeftSentence(triesLeft: number): string {
    if (triesLeft <= 0) {
        return 'No attempts are left.';
    }
    if (triesLeft === 1) {
        return 'You have 1 attempt left. A wrong code now declines this payment.';
    }
    return `You have ${triesLeft} attempts left.`;
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
    // `Save notes, no decision` named the box it used to save rather than what the press does, and
    // that box is gone. The press now writes whatever the two boxes hold, either or neither, so the
    // label says the one thing that is true of all of those: no verdict is recorded.
    ANNOTATE: 'Save without a decision',
};

/** What one of the three decision buttons says. Identical on both desks, which is the point. */
export function decisionActionLabel(action: FraudDecision): string {
    return DECISION_ACTION[action];
}

/**
 * What the analyst's own words about a verdict are called, wherever either desk says it.
 *
 * ONE word for the box it is typed into and for the line it is read back on, which is what stops
 * the field being written under one name and printed under another.
 *
 * It says `comment` and it may not say `reason`, and that is the correction rather than a
 * preference. The old caption was `Reason for this decision`, and the server was storing what was
 * typed under it by appending into the alert's own `reason`, which is the sentence the rules wrote
 * about the payment. So one line on screen carried the bank's suspicion and a person's conclusion
 * with nothing between them, and a reader had no way to tell which half they were reading. The two
 * are now two fields, and they have to be two words: see {@link ALERT_REASON_LABEL}.
 *
 * It also names no verdict, which the two desks' old captions did not manage either. The comment
 * goes on the wire whichever of the three buttons is pressed, so a caption reading `reason for
 * declining` tells an analyst clearing an alert that what they wrote is for a refusal they are not
 * making.
 */
export const DECISION_COMMENT_LABEL = 'Analyst comment';

/**
 * What the alert's own `reason` is called, which is now only what the rules said.
 *
 * A name it did not need while it was one line saying two things, and needs now that it is not: two
 * blocks of prose sit on the alert panel, one from the bank and one from a colleague, and each has
 * to say whose it is. `Reason` alone over the first would be the shorter word next to the longer
 * one and would read as the general case of it.
 *
 * The queue keeps its `Reason` column heading, from fields.ts, and there is no ambiguity to resolve
 * there: a queue row carries no comment and never will.
 */
export const ALERT_REASON_LABEL = 'Why this alert was raised';

/**
 * The words for holding an alert: the two controls, the alert nobody holds, and the two positions
 * of the filter that reads the queue by it.
 *
 * One vocabulary because there is one behaviour behind it. Assignment is a control and a filter
 * and not a field: the only name that can be written is the analyst's own, so there is nobody to
 * type and no colleague to hand an alert to, and the queue is read either as mine or as all of it.
 *
 * `unassigned` is a word and not a dash, in the panel that lists facts one to a line and in the
 * queue column alike. Both desks had already reached for it in the panel, and the column now
 * decides the same way for the reason {@link NOTE_AUTHOR_UNKNOWN} gives: an empty cell under a
 * heading of names reads as a name somebody withheld, and nothing is being withheld.
 */
export const TAKE_ALERT = 'Take this alert';
export const RELEASE_ALERT = 'Release';
export const UNASSIGNED = 'unassigned';
export const ASSIGNED_TO_ME = 'Mine';
export const ASSIGNED_TO_ANYONE = 'All';

/**
 * The three panels a fraud desk is made of, named once.
 *
 * They were named twice and the two disagreed about more than case. One platform titled the middle
 * panel `Alert Detail: Review Suspicious Transaction`, which is not a name: it tells an analyst who
 * has opened the fraud desk what the fraud desk is for, on every alert they read, in the largest
 * type on that half of the screen. A panel title is read a hundred times a day and has to survive
 * being read a hundred times, which the short form does and a slogan does not.
 *
 * Sentence case, because the rest of both applications is in sentence case and a title in Title
 * Case reads as a heading from somewhere else. Plural on the queue and on the details, singular on
 * the decision: the first two are panels about many alerts and one alert's many facts, the third is
 * one press.
 */
export const ALERTS_QUEUE_TITLE = 'Alerts queue';
export const ALERT_DETAILS_TITLE = 'Alert details';
export const DECISION_TITLE = 'Decision';

/**
 * What a list says before it has anything to show, and the control that asks the queue again.
 *
 * Both desks had these words and one of them had them as literals inside its markup, so the two
 * could be changed apart. They are the same sentence about the same request and there is no idiom
 * in either of them: what differs between the platforms is where the button sits and what it is
 * skinned like, and neither of those is a word.
 *
 * The table of payments takes the same argument and had gone one further: its word was typed as a
 * literal in three places, the customer's own history, the desk inside the customer application and
 * the workstation's desk. The three agreed, which is the state a word is in just before it drifts
 * rather than evidence that it will not.
 *
 * ONE name for those three, although they are two different lists. The customer's history and the
 * history beside an alert are the same rows read by two readers, so a second constant for the
 * customer's half would be a second word with nothing to keep it equal to the first: the moment one
 * of them is reworded, one role reads a sentence the other role never sees.
 *
 * Each names what is loading rather than saying `Loading…` twice. The two lists are on screen
 * together on both desks, and a reader who glances at either learns which request is out.
 */
export const QUEUE_LOADING = 'Loading alerts…';
export const PAYMENTS_LOADING = 'Loading payments…';
export const REFRESH = 'Refresh';
export const REFRESH_BUSY = SHOW_MORE_BUSY;

/**
 * The checkbox that puts the hidden alerts back, and the only name it has.
 *
 * Capital S because it is a control's label rather than a phrase in a sentence, and because the
 * note below prints this string inside a sentence to point at it: a lower case name would read as
 * a description of the box and leave the reader hunting for what to press. One platform had it
 * twice over, as a row label reading `Withdrawn` beside a lower case restatement of the same
 * instruction, which is two names for one checkbox on one screen.
 */
export const SHOW_WITHDRAWN_ALERTS = 'Show alerts on cancelled payments';

/**
 * Why the queue is shorter than the counters above it say.
 *
 * It said the whole mechanism: that a payment the customer withdrew and one an analyst declined
 * both end as Declined, that the desk cannot hide the first without the second, and that the
 * counters go on counting both. All true, and all of it an implementation note delivered to
 * somebody who wants to know why a row is missing and how to get it back. Those are the two things
 * this says now, in one line, because a paragraph above a queue is a paragraph nobody finishes.
 *
 * `Declined` is the reader's word for the status and comes from the table at the top of this file,
 * so the sentence and the rows it explains say the same thing; the wire's spelling in capitals was
 * a value nobody outside the server has to know. The control is named by the label the checkbox
 * actually carries, for the same reason.
 */
export function hiddenAlertsNote(hidden: number): string {
    const declined = transferStatusLabel('DECLINED', 'analyst');
    return hidden === 1
        ? `1 alert is hidden, on a payment that ended ${declined}. ${SHOW_WITHDRAWN_ALERTS} brings it back.`
        : `${hidden} alerts are hidden, on payments that ended ${declined}. ${SHOW_WITHDRAWN_ALERTS} brings them back.`;
}

/**
 * Why the list is empty, in the words that stop it contradicting the numbers above it.
 *
 * A bare "No alerts" under a strip reading `Whole queue, before any filter, 2` is a contradiction
 * until the sentence says which alerts it means: the strip counts the queue and the list is what
 * the filters matched, and both are true. The desk opens filtered, on New with withdrawn payments
 * hidden, so the filtered branch is the ordinary case rather than the exception, and it points at
 * the row of controls that would widen it.
 *
 * A queue with nothing in it at all says so plainly instead, because there is no filter to blame
 * and sending the reader to the controls would waste the only line the empty list has.
 *
 * Four wordings across two desks became these two. The exclusion counts as a filter: it is set
 * from a control on that same row, and an analyst who cannot see the alert they are looking for
 * has to be told that something on the screen is narrowing the list, not which something.
 */
export function emptyQueueNote(filters: AlertFilters): string {
    const filtered = Boolean(
        filters.state ||
            filters.assignee ||
            filters.minAmount ||
            filters.maxAmount ||
            filters.createdFrom ||
            filters.createdTo ||
            filters.excludeTransferStatus?.length,
    );

    return filtered
        ? 'No alerts match the filters above.'
        : 'There are no alerts in the queue.';
}

/**
 * What the counters strip counts, which is the whole point of the line and was said on one desk
 * only.
 *
 * `Whole queue: 2 alerts.` and `Whole queue, before any filter, 2:` are not the same claim. The
 * numbers are taken before any filter and before any page, so they go on saying how much work
 * exists while the analyst reads three rows of a filtered list, and a strip that does not say so
 * reads as a miscount of the rows underneath it.
 */
export const QUEUE_COUNTERS_BASIS = 'Whole queue, before any filter';

/**
 * The same claim for a column too narrow to carry the long one.
 *
 * The workstation puts this strip at the foot of a queue panel that is 420px wide, and the long
 * form leaves no room on that line for the numbers it introduces, so the strip broke into a
 * ragged stack of one phrase and four counts. The short form is the wording the workstation used
 * before the long one was written, and it says the same thing: the qualifier is what the numbers
 * are counted on, and on a strip that carries nothing else there is nothing for it to be confused
 * with.
 *
 * Two forms of one sentence, declared together so they cannot drift into two sentences. If the
 * wording of either changes, both change here.
 */
export const QUEUE_COUNTERS_BASIS_NARROW = 'Whole queue';

/** One state of the line: the word for it, and how many alerts are in it. */
export interface QueueCounterCell {
    /** The server's own value, so a skin can key a list or a style off it. */
    state: string;
    /** What that state is called, from {@link alertStateLabel} and never typed again. */
    label: string;
    count: number;
}

/**
 * The three states and their counts, in the order they are read.
 *
 * New first, because it is the work waiting; then the two verdicts, in the order the buttons under
 * the panel take them. Both desks had this list, one as three labelled cells and one as three
 * words typed into a sentence, and the typed one said `confirmed fraud` and `cleared` where the
 * rows below it said `Confirmed fraud` and `Cleared`.
 *
 * Cells rather than a finished line because {@link queueCountersSentence} is built out of them,
 * and exported beside it so a test can name one count without parsing the sentence back apart.
 * Nothing on either desk prints a cell on its own: a strip of separate figures and a sentence are
 * two different claims about the same three numbers, and the analyst who moves between the two
 * applications reads them as two different measurements.
 */
export function queueCounterCells(counters: AlertCounters): readonly QueueCounterCell[] {
    return [
        { state: 'NEW', label: alertStateLabel('NEW'), count: counters.newCount },
        {
            state: 'SUSPICIOUS',
            label: alertStateLabel('SUSPICIOUS'),
            count: counters.suspiciousCount,
        },
        { state: 'OK', label: alertStateLabel('OK'), count: counters.okCount },
    ];
}

/**
 * How many alerts there are, which is the sum of the three and not a fourth number off the wire.
 *
 * The three states are the only three an alert can be in, so their sum is the queue. Asking the
 * server for a total beside them would be one fact sent twice, and the two halves can disagree.
 */
export function queueCounterTotal(counters: AlertCounters): number {
    return counters.newCount + counters.suspiciousCount + counters.okCount;
}

/**
 * The three numbers as one sentence, which is how both desks say them.
 *
 * There used to be two shapes: a caption on the customer application and a stack of labelled
 * figures at the foot of the workstation's queue panel, with a second, shorter wording of the
 * qualifier written for the narrower column. Two shapes of one fact is one fact stated twice, and
 * the short wording dropped the half that matters, that the counts are taken before any filter.
 * The stack also read as a heading over the rows beneath it, which are on a different basis.
 *
 * One sentence answers both: it wraps in a 420px column instead of breaking into a ragged column
 * of counts, and it carries its own qualifier and its own full stop wherever it is put. Where the
 * line sits and what it is set in stays the skin's business; what it says stops here.
 *
 * How much of the filtered list is on screen is not in it. That number comes from the page rather
 * than from the counters, and it belongs on its own line beside the rows it is about.
 */
export function queueCountersSentence(counters: AlertCounters): string {
    const cells = queueCounterCells(counters)
        .map((c) => `${c.label} ${c.count}`)
        .join(', ');
    return `${QUEUE_COUNTERS_BASIS}, ${queueCounterTotal(counters)}: ${cells}.`;
}

/**
 * What the panel beside the queue says when it is holding nothing, and while it is filling.
 *
 * The invitation names the queue and not a position. `Select an alert on the left` is true of one
 * of the two layouts and describes furniture rather than the thing to press, and it stops being
 * true at the width where the two panels stack.
 *
 * The loading line names what is loading. `Loading detail…` sat in a panel next to a queue that
 * was also capable of loading, and a reader who glanced at it learned only that something was.
 */
export const SELECT_ALERT = 'Select an alert from the queue.';
export const ALERT_DETAIL_LOADING = 'Loading alert details…';

/** An empty payment history, with the full stop that every other sentence on these screens has. */
export const NO_HISTORY = 'No history.';

/**
 * The invitation on an empty decision panel, which is a different sentence from the one above.
 *
 * The panel beside the queue asks to be given an alert to show; this one asks to be given an alert
 * to act on, and a reader looking at both at once must not read one sentence twice.
 */
export const SELECT_ALERT_TO_DECIDE = 'Select an alert to take a decision.';

/**
 * The hint under the box the comment is typed into, and the caption and hint of the box beside it.
 *
 * THE SECOND BOX CHANGED WHAT IT DOES, so it changed its name. It used to be captioned `Internal
 * notes` and it held the whole of an alert's notes as one editable blob: whatever was left in it
 * was filed over what was there, an emptied box included, so two analysts on one alert erased each
 * other. It now adds ONE entry to a journal that is never overwritten, and a caption naming the
 * thing rather than the act would go on inviting the reader to edit what a colleague wrote. So the
 * caption is a verb, and it is the only caption in either desk's decision panel that is one.
 *
 * The captions still divide by audience, which is the difference between the two boxes and was the
 * one true half of the old wording: the comment rides with the verdict and its substance reaches
 * the customer on a refusal, and an entry in the journal reaches colleagues and nobody else.
 *
 * Both placeholders are examples of what to write rather than repetitions of the caption, which is
 * what a placeholder is worth. Neither says `optional`: what happens to an empty box is stated
 * once, in the hint under the buttons, where it applies to both of them.
 */
export const DECISION_COMMENT_PLACEHOLDER =
    'What you concluded, stored on the alert with your verdict.';
export const DECISION_NOTE_LABEL = 'Add a note';
export const DECISION_NOTE_PLACEHOLDER =
    'One entry for the case notes, kept for whoever reads this alert next.';

/**
 * What the journal is called, and what it says when it is empty.
 *
 * `Case notes` and not `Notes`, because `Notes` is what the blob was called and the panel has to
 * read as a record rather than as a field. It is plural, being a list, and the column that holds
 * one of them is singular; both are settled once, here and in fields.ts, so the panel and its
 * heading cannot end up naming the same thing two ways.
 *
 * The empty sentence is built like {@link NO_HISTORY} and stops in the same place. It says the
 * journal is empty and does not invite anybody to write in it: the box that does that is three
 * inches away with a caption of its own, and a second invitation would be the screen asking twice.
 */
export const ALERT_NOTES_TITLE = 'Case notes';
export const NO_ALERT_NOTES = 'No notes on this alert.';

/**
 * The word for an entry whose author was never recorded.
 *
 * ONE entry per alert can carry it, at most: the one carried over from the single notes column that
 * preceded the journal, which kept text and kept no name. Everything written since records who
 * wrote it, so this is a word about the past rather than a state an analyst can produce.
 *
 * A word and not the table's dash, for the reason {@link UNASSIGNED} is one: a blank cell in a
 * column of names reads as a name withheld, and nothing is being withheld. `unknown` and not
 * `nobody`, which would claim the entry wrote itself.
 *
 * Lower case, because it is a word standing in for a value and not a value of its own.
 */
export const NOTE_AUTHOR_UNKNOWN = 'unknown';

/** Who wrote a journal entry, or the word above when the name was never kept. */
export function noteAuthorLabel(author: string | null | undefined): string {
    return author || NOTE_AUTHOR_UNKNOWN;
}

/**
 * What a decision button says while its own press is in flight.
 *
 * On the button that was pressed and on no other, which is what neither desk was doing. One put
 * this word on Approve whichever of the three was pressed, so Decline made Approve announce the
 * work; the other said nothing at all and left three live looking buttons over a request already
 * out. All three are disabled while any one of them is working, because the server takes one
 * verdict per alert and the second press is a refusal that would take the typed notes with it, but
 * only the pressed one changes its word: a row of three buttons all reading `Applying…` would say
 * three decisions were being taken.
 */
export const DECISION_BUSY = 'Applying…';

/**
 * The one thing about a decision an analyst cannot see for themselves.
 *
 * It replaces a standing paragraph of three sentences. Two of them said what the screen already
 * says: the first repeated the Approve button, which is labelled with what it does, and the third
 * described the notes journal, which is a list anybody can look at. What is left is the sentence
 * nothing on the screen can show, because it is about a payment that has already gone: the verdict
 * is recorded and the money is not coming back.
 *
 * Shown only against a payment in that state, which is why it is a sentence about a condition and
 * not guidance standing under the buttons at all times.
 */
export const DECLINE_ALREADY_SENT =
    'This payment has already been sent. Declining records the verdict without reversing it.';

/**
 * Why Decline is dead while the comment box is empty.
 *
 * The server refuses the same press with the catalogue's general validation sentence, which names
 * no box, so if the screen does not say what is missing then nothing does. That makes this the
 * load bearing line under the buttons rather than one more piece of guidance.
 *
 * Two facts and no more: it is required, and it is not internal. Which box is meant is carried by
 * the star on that box's own label rather than spelled out here, so the sentence does not have to
 * name a field the reader is already looking at.
 *
 * HERE BECAUSE IT DRIFTED. Both desks wrote this sentence for themselves, each recording in a
 * comment that the other was being given the identical string, and the two strings were not
 * identical: one asked for "the Analyst comment box" and one for "the analyst comment filled in",
 * at ninety one and a hundred and sixty three characters. One analyst does this job in two windows.
 */
export const DECLINE_NEEDS_COMMENT = 'Required to decline. The customer is shown it.';

/**
 * The names of the first two cards under {@link ALERT_DETAILS_TITLE}.
 *
 * Four cards stand under that one heading and they are four different subjects: the alert, the
 * payment it was raised on, everything else the customer has paid, and what the desk has written
 * down. Two of the four said which they were and two opened straight into their first fact, so
 * `Alert: ALERT-2 (New)` and `Transfer: TR-9` were doing double duty as the name of the box and as
 * the first thing inside it. An analyst comes to this screen for one of the four and reads down
 * the others to find it.
 *
 * Both carry a determiner, which the two names below and at {@link ALERT_NOTES_TITLE} do not
 * need: `Alert` over a line reading `Alert:` names nothing twice over, while `This alert` reads as
 * the box rather than as the field. `The alerted payment` and not `Payment`, because the card is
 * about the one payment the alert was raised on and the card under it is full of others.
 *
 * Here rather than in the client that drew them first, which is the whole of why the other two
 * names are here: a box named locally is named again on the second platform, by somebody else, in
 * their own words, and then one desk is read by an analyst who works in both.
 */
export const ALERT_CARD_TITLE = 'This alert';
export const PAYMENT_CARD_TITLE = 'The alerted payment';

/**
 * What the history beside an alert is a history OF.
 *
 * The two desks headed one table with two sentences and neither was true: one said "last 10
 * transfers from this account" and one said "Customer & Transfer history", while the table held
 * the payments of one account out of the customer's two. It now holds the customer's payments
 * across every account they hold, and the heading has to say so, or the account marked in the
 * column below is marked for no stated reason.
 *
 * It used to end in "(last 10)", which was true of a table that could not be made longer. The
 * table pages now and states its own count underneath, so after one press of Show more the
 * parenthetical contradicted the line below it. Where a list stops is what the count line says,
 * on every list in both applications; a heading that says it too is a second answer that goes
 * stale the moment the first one changes.
 */
export const CUSTOMER_HISTORY_TITLE = 'Customer history, all accounts';

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
 * The word over the sentence {@link describeDecision} returns.
 *
 * A block that appears under three buttons after one of them is pressed needs to say that it is the
 * answer to the press, because the sentence inside it does not: `Alert cleared.` is a statement
 * about the alert and reads, at a glance, like one more of the panel's own captions. The title is
 * what makes the sentence a reply.
 *
 * `Result` and not `Decision`, which is the panel this block sits in and is already the heading
 * above the buttons. Two headings reading the same word, one inside the other, name nothing.
 *
 * Both desks had reached this word independently and both typed it, which is the cheapest kind of
 * agreement to lose: nothing fails on either build the day one of them is reworded, and the two
 * platforms then answer one press with two different names for the answer.
 */
export const DECISION_RESULT_TITLE = 'Result';

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
    //
    // `Saved on the alert` and no longer `Notes saved`, because the press writes two different
    // things and may write either, both or neither: the comment, which replaces what a previous
    // verdict left, and an entry appended to the case notes. Naming one of them would announce the
    // wrong one half the time, and this sentence is read after the press rather than instead of
    // looking at the panel, which now shows both.
    return 'Saved on the alert. No decision was taken: the alert is still open and the transfer is unchanged.';
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

/**
 * The name over a failure box, wherever either application draws one.
 *
 * It belongs beside the sentences above it for the same reason they are here: the words a refusal
 * is delivered in are not skin. Each application's ErrorBox is its own markup, a summary block on
 * one platform and a bordered stack on the other, and each defaults its title to this word by
 * typing it. Two literals agreeing is what this file exists to replace.
 *
 * A DEFAULT and not the title of every failure, which is what keeps it to one word. A screen with a
 * better sentence for its own refusal passes one, and that is where a sentence belongs; what stands
 * here has to be true of a sign in that was rejected, a queue that could not be reached and a
 * verdict the server would not take, and the only honest word for all three is the short one.
 *
 * It is a name rather than an apology because of what the box replaces. A bordered red block with
 * nothing over it reads as a fault of the screen; named, it reads as the answer to whatever was
 * just asked, which is what the reader is waiting for.
 */
export const FAILURE_TITLE = 'Error';

/**
 * Which clock every instant on every screen is printed against.
 *
 * The formatter has always rendered in Europe/Prague, so the values were never wrong; what was
 * missing is the sentence that tells the reader so. Without it a queue timed 20:52 is read against
 * whatever zone the device is set to, and two people comparing the same payment over a telephone
 * are reading two different times from the same row.
 *
 * A label rather than a sentence. It stands beside a table of times as the unit those numbers are
 * in, which is the whole of what a reader needs from it; the fuller wording it replaces went on to
 * explain that this is the bank's clock and not the device's, which is what naming a zone means.
 *
 * It says time zone and not times, because it rides the same line as the count of rows on screen
 * and "Showing 2 · Times ..." puts two different senses of the same word a few characters apart.
 * Naming the thing it actually is costs one word and reads as a caption rather than as a variable.
 */
export const TIMES_ZONE_NOTE = 'Time zone: Europe/Prague';

/**
 * How long the comment on a decision may run, and how long a journal entry may.
 *
 * The server caps neither: both columns are TEXT. These are the screens' own measures and they
 * differ because what happens to the two afterwards differs. The comment on a decline is appended
 * to the alert's reason, and the reason is drawn whole inside a queue row, so one analyst writing
 * a paragraph reformats the queue for everybody; a sentence of justification fits in 280 and a
 * paragraph does not. An entry in the journal is read one alert at a time, where length costs
 * nobody anything but the writer.
 *
 * Declared here so both desks count against the same number. A counter under a box is a promise
 * about what the box will take, and two platforms promising different things about the same field
 * is the drift this file exists to stop.
 */
export const MAX_DECISION_COMMENT = 280;
export const MAX_DECISION_NOTE = 1000;

/**
 * What a sign in that is taking its time says while it takes it.
 *
 * Checking a password here is deliberately expensive, so a sign in runs for seconds rather than
 * for the moment a reader expects, and the only thing that changed on screen was the word on a
 * greyed button. A button that says nothing for eight seconds reads as a button that did not take
 * the press, and the reasonable thing to do about that is press it again.
 *
 * It says what is happening rather than asking for patience, because the reader's question is
 * whether their press arrived, and an apology does not answer it.
 */
export const SIGN_IN_SLOW_NOTE = 'Checking your details with the bank. This takes a few seconds.';

/**
 * How long a sign in may run before it says so.
 *
 * Long enough that a sign in on a warm machine never draws it, short enough to arrive before the
 * reader reaches for the button a second time.
 */
export const SIGN_IN_SLOW_MS = 1500;
