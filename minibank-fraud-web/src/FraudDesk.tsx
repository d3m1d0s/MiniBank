import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
    fetchAlerts,
    fetchAlertDetail,
    fetchAlertHistory,
    fetchHiddenAlerts,
    formatMoney,
    parseAmount,
    postFraudDecision,
    releaseAlert,
    takeAlert,
    type AlertCounters,
    type AlertDetail,
    type AlertFilters,
    type AlertQueueItem,
    type FraudDecision,
    type HistoryItem,
    type Page,
} from './api';
import { amountRangeProblem } from '@shared/alertFilters';
/*
 * Straight from the shared module rather than through ./api, because the barrel says what this
 * application asks of the API and a fee line is drawn from a value that already arrived.
 */
import { formatFeeLine } from '@shared/money';
import { describeApiError, describeApiFailure, type ApiFailure } from '@shared/apiErrors';
import {
    FIELD_LABEL,
    HISTORY_FIELD_LABEL,
    HISTORY_COLUMN_FIELDS,
    HISTORY_NOTE_FIELD,
    type HistoryField,
    type HistoryRowCells,
    type QueueRowCells,
} from '@shared/fields';
import {
    NOT_RECORDED,
    formatAlertId,
    formatDateTime,
    formatIban,
    formatTransferId,
} from '@shared/format';
import {
    ASSIGNED_TO_ANYONE,
    ASSIGNED_TO_ME,
    CUSTOMER_HISTORY_TITLE,
    DECISION_REASON_LABEL,
    RELEASE_ALERT,
    TAKE_ALERT,
    UNASSIGNED,
    alertStateLabel,
    alertStateTone,
    authMethodLabel,
    bankBoundaryMark,
    bankBoundaryLabel,
    decisionActionLabel,
    decisionLabel,
    describeDeclineReason,
    describeDecision,
    transferStatusLabel,
    transferStatusTone,
} from '@shared/glossary';
import {
    SHOW_MORE,
    SHOW_MORE_BUSY,
    appendPage,
    hasMore,
    nextPage,
    showingLine,
} from '@shared/paging';

/** The transfer status a withdrawn payment ends in. */
const WITHDRAWN = 'DECLINED';

/**
 * How many alerts arrive at a time.
 *
 * The server's own default, and the same number the customer application's desk asks for. A queue
 * is meant to be seen entire, and the question at its foot is how much work is left rather than
 * whether to read on; the count line answers that one and the counters above the tray answer a
 * different one again.
 */
const PAGE_SIZE = 25;

/**
 * How many of the customer's payments arrive with the first look at an alert.
 *
 * Ten, which is what the detail used to carry and all it could ever carry. The table is evidence
 * inside a panel that is scrolled past on the way to the verdict, so it opens at the length it
 * has always had and lengthens on request; a first page of twenty-five would push the decision
 * block's own evidence out of reach to show payments nobody asked for.
 */
const HISTORY_PAGE_SIZE = 10;

/**
 * The control that asks the queue again, and what it says while it is asking.
 *
 * This is the one screen in the product whose contents change without anybody touching it: an
 * alert is raised by the server, and until now the only way to see it was to change a filter and
 * change it back, or to reload the window and lose the case on the right. The refusal sentences
 * for a lost race even promise a reload the screen did not offer.
 *
 * "Loading…" while it waits, which is what the tray and the Show more at the foot of the queue
 * both say: one word for one wait in one panel.
 */
const REFRESH = 'Refresh';
const REFRESH_BUSY = 'Loading…';

/** What the tray says while the first page is still on its way. */
const QUEUE_LOADING = 'Loading alerts…';

/**
 * Which amount bound is being typed. The two boxes are one control by role, so one function
 * handles both and this tells them apart.
 */
type AmountBound = 'min' | 'max';

/**
 * A page of the customer's payments, and the alert it was fetched for.
 *
 * The id travels with the rows because the panel now makes two requests about one alert and the
 * history is the slower of them. Without it a page that arrives after the analyst has clicked the
 * next card is drawn under that card's facts, which is a worse fault than a table that says it is
 * still loading: nothing on the row would say the payments belong to somebody else.
 */
interface HistoryPage {
    alertId: number;
    rows: HistoryItem[];
    last: Page<HistoryItem>;
}

/** A refusal from the history route, carrying the alert it was asked about for the same reason. */
interface HistoryProblem {
    alertId: number;
    failure: ApiFailure;
}

/**
 * Why the queue is shorter than the counters above it say, in the one case where the desk itself
 * is the cause.
 *
 * One transfer status carries two meanings that are opposites here. A payment the customer
 * withdrew and a payment an analyst declined are both DECLINED, so the default that hides the
 * first, which is right, since a withdrawn payment has nothing left to decide, also hides every
 * alert this desk has confirmed as fraud. Opening on Cleared showed an empty list under a strip
 * reading Cleared: 1, and nothing on the screen said why.
 *
 * The rule is not changed and the counters are not changed: both are right, and neither is going
 * to be bent to paper over a collision in the server's vocabulary. What was missing is this
 * sentence and the number in it, and the number comes from the endpoint that answers exactly the
 * question, so the screen never guesses at it.
 */
function hiddenNote(count: number): string {
    const subject = count === 1 ? '1 alert is' : `${count} alerts are`;
    return `${subject} hidden from this list by the Withdrawn box above. A payment the customer`
        + ' withdrew and a payment an analyst declined both end as Declined, so hiding the first'
        + ' hides the second. Tick the box to see them.';
}

/**
 * What an empty tray says, and it is the only thing the tray says when it says this.
 *
 * It names the filters, because the strip under it counts the whole queue before any of them: a
 * bare "No alerts" standing over "Whole queue, 2" is a contradiction until the first sentence says
 * which alerts it means. Both are true and both are worth printing; what was missing is the word
 * that tells a reader they are answers to two different questions.
 *
 * A queue with nothing in it at all says so plainly instead, since there is no filter to blame.
 */
function emptyQueueLine(applied: AlertFilters): string {
    const filtered = Boolean(
        applied.state
        || applied.assignee
        || applied.minAmount
        || applied.maxAmount
        || applied.createdFrom
        || applied.createdTo
        || applied.excludeTransferStatus?.length,
    );
    return filtered ? 'No alerts match these filters.' : 'There are no alerts in the queue.';
}

/**
 * What sizes and aligns each history column.
 *
 * WHICH columns there are, and in what order, is no longer written out here: the list comes from
 * the shared field set, and the split between the fields that take a column and the one that
 * takes a row of its own is named there too. A field added to the union now turns up in this
 * table on the next build, where a hand kept list of five would have left it out in silence.
 *
 * What stays local is the width and the alignment. Those are this pane's own: the same five
 * fields are laid across a thousand pixels in the customer application and down a pane with a
 * floor of 360 here, which is the idiom difference the shared set exists to allow.
 *
 * The amount is marked where the cell is built, and the heading takes the same mark from the same
 * entry: alignment belongs to the column, so the word has to stand over its own digits rather than
 * at the far edge of them. Counting header cells to find the column to right align, which is what
 * the customer application's stylesheet still does, breaks silently the day a column is added.
 *
 * The route is account numbers stacked in one column and still carries no cell class: what has to
 * be styled there is each of the lines, and each is marked where it is built, for the same reason
 * the amount is.
 */
const HISTORY_COLUMN_CLASS: Partial<Record<HistoryField, { col: string; cell?: string }>> = {
    id: { col: 'col--id' },
    createdAt: { col: 'col--created' },
    amount: { col: 'col--amount', cell: 'cell--amount' },
    status: { col: 'col--status' },
    route: { col: 'col--route' },
};

/**
 * A queue entry's nine fields, ready to be laid out.
 *
 * Built through the shared row type so that a field the server sends and this desk forgets is a
 * build failure rather than something an analyst discovers is missing. The workstation reads a
 * card down and the customer application reads a table across, so each builds its own cells and
 * only the field set is shared.
 */
function queueCells(a: AlertQueueItem): QueueRowCells<ReactNode> {
    return {
        alertCode: a.alertCode,
        transferCode: a.transferCode,
        // The two words that say whether this row is still somebody's problem, carrying the same
        // treatment they carry in the history table below and in the customer application. Without
        // it the tray was the one place in the product where a cleared alert and a new one were set
        // in the same grey, which is the opposite of what a triage list is for.
        state: <span className={`tone-${alertStateTone(a.state)}`}>{alertStateLabel(a.state)}</span>,
        transferStatus: (
            <span className={`tone-${transferStatusTone(a.transferStatus)}`}>
                {transferStatusLabel(a.transferStatus, 'analyst')}
            </span>
        ),
        amount: formatMoney(a.amount),
        shortReason: a.shortReason,
        // The card has no column headers, so the two triage fields carry their own word.
        riskScore: `Risk ${a.riskScore ?? NOT_RECORDED}`,
        assignee: a.assignee || UNASSIGNED,
        createdAt: formatDateTime(a.createdAt),
    };
}

/**
 * One history row, ready to be laid out.
 *
 * `alertedIban` is the account the alert was raised on, which the block of facts above this table
 * names. Compared by value and not normalised: both strings come out of the same column of the
 * same response, so they agree in case and spacing by construction.
 */
function historyCells(h: HistoryItem, alertedIban: string | null): HistoryRowCells<ReactNode> {
    const alerted = alertedIban !== null && h.fromIban === alertedIban;
    const boundary = bankBoundaryMark(h.toIbanInBank);
    const feeLine = formatFeeLine(h.fee);

    return {
        id: formatTransferId(h.id),
        createdAt: formatDateTime(h.createdAt),
        // The amount the customer sent, and under it what the bank added to it. Two lines of one
        // sum, stacked like the two account numbers next door, so the column can be read down.
        // Every row has the second line: on a payment the desk stopped it is the price rather than
        // a charge, and the status beside it is what says so.
        amount: (
            <>
                <span className="amount-value">{formatMoney(h.amount)}</span>
                {feeLine && (
                    <span className="amount-fee">
                        <span className="visually-hidden">{FIELD_LABEL.fee}: </span>
                        {feeLine}
                    </span>
                )}
            </>
        ),
        fee: feeLine,
        status: (
            <span className={`tone-${transferStatusTone(h.status)}`}>
                {transferStatusLabel(h.status, 'analyst')}
            </span>
        ),
        // Source above beneficiary: the money reads down the cell, and the number the eye is
        // hunting sits on the strong line. The word after the beneficiary appears only where the
        // money never left the bank, which is the row this desk can still do something about.
        route: (
            <>
                <span className={alerted ? 'route-from route-from--alerted' : 'route-from'}>
                    {alerted && <span className="visually-hidden">Alerted account: </span>}
                    {formatIban(h.fromIban)}
                </span>
                <span className="route-to">
                    {formatIban(h.toIban)}
                    {boundary && <> <span className="route-boundary">{boundary}</span></>}
                </span>
            </>
        ),
        declineReason: describeDeclineReason(h.declineReason),
    };
}

/**
 * The locale an ambiguous amount is read in.
 *
 * Only `1,234` needs it: the one string that is a valid number under both the Czech and the
 * English convention and means two different things under them. It is read here rather than in
 * the parser because the shared modules are pure, so that their rules can be tested without a
 * browser, and this is the one thing in the reading that only a browser knows.
 */
function readerLocale(): string {
    return navigator.language || 'cs-CZ';
}

/**
 * A refusal: the sentences the shared table answers with, the reference under them, and the way
 * out where the caller has one to offer.
 *
 * A line each rather than one run of prose: the table sends what happened and, where there is one,
 * the thing to do about it, and the second is the half a reader acts on. The box around them is
 * the .error this window has always drawn.
 *
 * The reference is the only machine text left in here, and it is not for the analyst: it is what
 * makes a photograph of this box worth something to whoever runs the bank, now that the body of a
 * bad answer is dropped before it can reach a screen.
 *
 * The button is drawn only where a caller passed one, and a caller passes one only for a read. A
 * decision, a take and a release each have a press of their own already, and a second control
 * offering to send them again is how a refused verdict becomes two.
 */
function ErrorBox(props: { failure: ApiFailure }) {
    const { lines, reference, retry, retryLabel } = props.failure;
    return (
        <div className="error">
            {lines.map(line => <div key={line}>{line}</div>)}
            {reference && <div className="error-reference">{reference}</div>}
            {retry && (
                <div className="actions">
                    <button type="button" className="btn" onClick={retry}>{retryLabel}</button>
                </div>
            )}
        </div>
    );
}

export default function FraudDesk(props: { username: string; onLogout: () => void }) {
    /**
     * The desk hides withdrawn payments by default; the endpoint hides nothing by default.
     *
     * An alert whose payment the customer cancelled has nothing left to decide - the money is
     * not going anywhere - but it is still evidence and nothing has resolved it, so it stays
     * NEW and used to sit in this queue forever. Hidden here rather than resolved anywhere:
     * the alert's state is the analyst's verdict and no screen may write it.
     *
     * The same shape as the state filter above it: the server has no default, the desk has one,
     * and the control that undoes it is on screen.
     */
    const [filters, setFilters] = useState<AlertFilters>({
        state: 'NEW',
        excludeTransferStatus: [WITHDRAWN],
    });
    const [alerts, setAlerts] = useState<AlertQueueItem[]>([]);
    const [counters, setCounters] = useState<AlertCounters | null>(null);
    /**
     * The page that arrived, kept whole.
     *
     * The next request is counted from it and not from the rows on screen: those two stop
     * agreeing the moment an alert raised mid-session pushes a row from one page onto the next
     * and the repeat is dropped. It also carries the total the count line prints.
     */
    const [lastPage, setLastPage] = useState<Page<AlertQueueItem> | null>(null);

    /**
     * How many alerts the payment-status exclusion is keeping off the list right now.
     *
     * Answered by a route of its own rather than worked out from the two totals on screen: the
     * counters count the whole queue before any filter and the page counts what the filters
     * matched, so subtracting them would name every alert the state filter is also excluding and
     * blame the checkbox for it.
     */
    const [hiddenCount, setHiddenCount] = useState(0);
    const [hiddenErr, setHiddenErr] = useState<string | null>(null);

    const [selectedId, setSelectedId] = useState<number | null>(null);
    const [detail, setDetail] = useState<AlertDetail | null>(null);

    /**
     * Which alert the panel is open on, readable from inside a request that has already left.
     *
     * There was no such thing in any of the three trees, and every call that fills the panel is
     * an await followed by a bare setState. The panel makes two requests about one alert, both of
     * them slower than a click, so an analyst working down the tray was one late answer away from
     * reading the previous alert's payment under this alert's case number, with nothing on either
     * to say they did not belong together. Two more calls land in the same panel from a press,
     * the decision and the assignment, and they can land after the analyst has moved on too.
     *
     * A ref and not the selectedId state, because that is the point: a request holds the value it
     * was sent with, and this holds the value as it stands when the answer arrives. Every landing
     * below compares the two and drops what no longer belongs, the busy flag included: a stale
     * answer that clears the flag leaves the panel showing neither a case nor a wait.
     */
    const openAlert = useRef<number | null>(null);

    /**
     * The payments beside the alert, a page at a time, and what the route said if it refused.
     *
     * Read from the history route and not from `detail.history`, which carries ten rows and no
     * total: a panel that offers more has to know how many there are, and turning its page must
     * not make the server rebuild the alert, the payment, the account and the customer to answer
     * a question about none of them.
     */
    const [history, setHistory] = useState<HistoryPage | null>(null);
    const [historyErr, setHistoryErr] = useState<HistoryProblem | null>(null);

    /**
     * What was typed into each amount box, and what is wrong with it.
     *
     * Two states rather than one because they are two different things: the analyst sees what
     * they wrote, in whatever convention they wrote it, and the query carries the plain decimal
     * the server parses. The refusal is kept per box, because fixing the upper bound must not
     * silently forgive the lower one, and because it names what is wrong with that string rather
     * than only that something is.
     */
    const [amountText, setAmountText] = useState({ min: '', max: '' });
    const [amountReason, setAmountReason] = useState<Record<AmountBound, string | null>>({
        min: null,
        max: null,
    });

    // Four failures rather than four strings: the shared table answers with a statement of what
    // happened, the thing to do about it where there is one, the short reference for the small
    // type, and, on a read, the loader that would ask again. Rendering them is ErrorBox's job.
    const [listErr, setListErr] = useState<ApiFailure | null>(null);
    const [detailErr, setDetailErr] = useState<ApiFailure | null>(null);
    const [decisionErr, setDecisionErr] = useState<ApiFailure | null>(null);
    // Kept apart from the decision's own refusal: a lost race on the assignment costs the analyst
    // nothing they typed, and the sentence for it says so. Sharing one box would put a warning
    // about discarded work under a press that had none at stake.
    const [assignErr, setAssignErr] = useState<ApiFailure | null>(null);
    // This desk had no success state at all: after a decision the alert left the NEW-filtered
    // queue, the panel unmounted with it, and the analyst was left with an empty pane and no
    // statement of what had happened.
    const [decisionMsg, setDecisionMsg] = useState<string | null>(null);
    /*
     * Busy from the first render, before the effect below has asked for anything.
     *
     * The tray prints one sentence when it holds no cards, and with a flag that starts false that
     * sentence was "No alerts": the desk stated the queue was empty on the frame before it had
     * asked, every time it opened. A list is not empty until somebody has answered that it is.
     */
    const [busyList, setBusyList] = useState(true);
    const [busyMore, setBusyMore] = useState(false);
    const [busyDetail, setBusyDetail] = useState(false);
    const [busyHistory, setBusyHistory] = useState(false);
    const [busyHistoryMore, setBusyHistoryMore] = useState(false);
    const [busyDecision, setBusyDecision] = useState(false);
    const [busyAssign, setBusyAssign] = useState(false);

    const [decisionReason, setDecisionReason] = useState('');
    const [notes, setNotes] = useState('');
    /**
     * The notes as the alert carried them, which is what the box is compared against.
     *
     * The box is filled from the alert, so an analyst who takes any decision without touching it
     * would post back a copy of what a colleague wrote, and the wire cannot tell that copy from an
     * edit. Null on the wire means leave the stored notes alone and an empty string means clear
     * them, so both readings matter: unchanged sends nothing, and a box the analyst emptied on
     * purpose sends the empty string and clears the column.
     */
    const [notesLoaded, setNotesLoaded] = useState('');

    /*
     * The history that belongs to the alert on screen, and nothing else.
     *
     * Matched here rather than at each of the three places that read it, so a page fetched for
     * one alert can never be drawn under another's facts: the panel makes two requests and this
     * is the slower of them, and a payment row carries nothing that would say whose it is.
     */
    const shownHistory = detail && history && history.alertId === detail.alert.id ? history : null;
    const shownHistoryErr = detail && historyErr && historyErr.alertId === detail.alert.id
        ? historyErr.failure
        : null;

    // amountReason belongs in here beside filters. Typing something that cannot be read into an
    // already empty field leaves the filters untouched, so on filters alone nothing would re-run
    // and the analyst would be told nothing at all.
    useEffect(() => { void reloadList(); }, [filters, amountReason]);

    async function reloadList(keepSelection = false) {
        // Checked before the request, and the server checks it again. This half exists to name
        // which two numbers are the wrong way round; the server cannot, because no handler
        // echoes an exception message. The server half exists because the endpoint is reachable
        // without this screen.
        //
        // A box the parser refused is answered with the parser's own sentence, which says what
        // is wrong with that string. The shared guard's unreadable branch is the answer for a
        // caller with no sentence of its own, so by the time it runs both bounds are either
        // readable or empty.
        const problem =
            amountReason.min ??
            amountReason.max ??
            amountRangeProblem(filters, { min: false, max: false });
        if (problem) {
            setAlerts([]);
            setLastPage(null);
            setCounters(null);
            // No reference and no retry: nothing was sent, so there is no answer to quote, and
            // asking again with the same two boxes would be refused again. What fixes it is the
            // sentence itself, which names the bound to correct.
            setListErr({ lines: [problem], reference: null });
            // The flag starts true, and this branch returns without a request. Left alone it
            // would leave the tray saying it was still loading for as long as the box is wrong.
            setBusyList(false);
            // Nothing was asked, so nothing is being hidden by anything but the mistake in the
            // box, and the sentence about the Withdrawn filter would be an answer to a question
            // the desk never put.
            setHiddenCount(0);
            setHiddenErr(null);
            return;
        }

        try {
            setBusyList(true);
            setListErr(null);
            // From the top, and as wide as what is already on screen: a decision reloads this
            // list, and a queue that collapsed back to its first page every time an alert was
            // decided would lose the rows the analyst had opened out to reach it.
            const wanted = keepSelection ? Math.max(PAGE_SIZE, alerts.length) : PAGE_SIZE;
            const resp = await fetchAlerts(filters, 0, wanted);
            setAlerts(resp.alerts.items);
            setLastPage(resp.alerts);
            setCounters(resp.counters);
            if (!keepSelection && selectedId && !resp.alerts.items.some(x => x.id === selectedId)) {
                // The ref with the two states: the panel is being closed, so a detail or a
                // history page still in flight for that alert has nothing left to land on.
                openAlert.current = null;
                setSelectedId(null);
                setDetail(null);
            }
            // Asked with the filters the queue was just asked with, and asked after it: the two
            // answers are the two halves of one filter, so a hidden count taken under a different
            // query would explain a list that is not on the screen.
            void refreshHidden(filters);
        } catch (e) {
            // A read, so the box carries the way to ask again. The same filters, the same width,
            // and the selection is kept: a queue that failed once is the one place on this desk
            // where a second press costs nothing and is the whole of the answer.
            setListErr(describeApiFailure(e, 'alert-queue', {
                retry: () => void reloadList(keepSelection),
            }));
            // Both, and for one reason: the two of them explain the difference between the queue
            // and the list that has just failed to arrive, and a note about a filter under a
            // queue nobody could re-read is one more sentence between the analyst and the box
            // that says what happened.
            setHiddenCount(0);
            setHiddenErr(null);
        } finally {
            setBusyList(false);
        }
    }

    /**
     * How many alerts the payment-status exclusion is keeping off the queue.
     *
     * A page of one, because only the total is read. The rows are already reachable by ticking
     * the box that is hiding them, so a second copy of twenty-five queue entries would be fetched
     * to be thrown away.
     *
     * Excluding nothing is not asked at all: the route answers an empty page for it without
     * touching the store, and the desk knows that answer before it asks.
     */
    async function refreshHidden(applied: AlertFilters) {
        if (!applied.excludeTransferStatus?.length) {
            setHiddenCount(0);
            setHiddenErr(null);
            return;
        }

        try {
            const hidden = await fetchHiddenAlerts(applied, 0, 1);
            setHiddenCount(hidden.total);
            setHiddenErr(null);
        } catch (e) {
            // Said in the hint's own voice and not in an error box. Nobody asked for this list:
            // it is the desk explaining its own filter, and a failure to explain must not read as
            // a failure of the queue standing beside it, which has just answered.
            setHiddenCount(0);
            setHiddenErr(describeApiError(e, 'alert-hidden'));
        }
    }

    /**
     * The next page of the queue, appended under the cards already in the tray.
     *
     * The tray's own "Loading…" hint is not shown for this: that sentence means the first page,
     * and drawing it would blank a queue the analyst is reading. The button says so instead and
     * keeps its place while it does.
     */
    async function loadMore() {
        if (!lastPage) return;

        try {
            setBusyMore(true);
            setListErr(null);
            const resp = await fetchAlerts(filters, nextPage(lastPage), PAGE_SIZE);
            setAlerts(held => appendPage(held, resp.alerts.items));
            setLastPage(resp.alerts);
            setCounters(resp.counters);
        } catch (e) {
            // The retry is this page again and not the whole queue: the rows already in the tray
            // stay where they are, and the box is drawn under them, beside the button that was
            // pressed to get them.
            setListErr(describeApiFailure(e, 'alert-queue', { retry: () => void loadMore() }));
        } finally {
            setBusyMore(false);
        }
    }

    async function openDetail(id: number) {
        // Before anything is asked for, so an answer to the previous question can already tell
        // that it is late by the time it arrives.
        openAlert.current = id;
        setSelectedId(id);
        setDetail(null);
        // The two boxes at the foot of the panel, emptied with the case they were typed against.
        // What is in them is not a draft: the server files the reason as the reason this alert was
        // held and, on a decline, as the reason the payment was refused. A sentence about one
        // customer left standing under another's case is a press away from being their record.
        setDecisionReason('');
        setNotes('');
        setNotesLoaded('');
        setDecisionMsg(null);
        setDecisionErr(null);
        setAssignErr(null);

        // Both requests leave together. Neither answer is needed to build the other, and the
        // history is the slower of the two, so waiting for the alert before asking for it would
        // add a second round trip to the wait before any of it is on screen.
        void loadHistory(id);

        try {
            setBusyDetail(true);
            setDetailErr(null);
            const d = await fetchAlertDetail(id);
            if (openAlert.current !== id) return;
            setDetail(d);
            // The notes a colleague left, in the box that will send them back. Filled from the
            // alert rather than left empty, because an empty box under a Save button is an
            // invitation to replace what is stored with one line; see notesLoaded for what is
            // then sent.
            setNotes(d.alert.notes ?? '');
            setNotesLoaded(d.alert.notes ?? '');
        } catch (e) {
            if (openAlert.current !== id) return;
            setDetailErr(describeApiFailure(e, 'alert-details', {
                retry: () => void openDetail(id),
            }));
        } finally {
            // Guarded like the rest: a late answer that drops the flag would leave the panel
            // showing neither the case it is fetching nor the fact that it is fetching one.
            if (openAlert.current === id) setBusyDetail(false);
        }
    }

    /** The first page of the customer's payments, and the total this panel could never state. */
    async function loadHistory(id: number) {
        setHistory(null);
        setHistoryErr(null);
        try {
            setBusyHistory(true);
            const page = await fetchAlertHistory(id, 0, HISTORY_PAGE_SIZE);
            if (openAlert.current !== id) return;
            setHistory({ alertId: id, rows: page.items, last: page });
        } catch (e) {
            if (openAlert.current !== id) return;
            setHistoryErr({
                alertId: id,
                failure: describeApiFailure(e, 'alert-history', {
                    retry: () => void loadHistory(id),
                }),
            });
        } finally {
            if (openAlert.current === id) setBusyHistory(false);
        }
    }

    /**
     * The next page of payments, under the rows already in the table.
     *
     * The alert is taken from the page that arrived rather than from the selection, so a press
     * that lands while the analyst is moving on still asks about the alert whose rows it is
     * lengthening, and the answer is filed under that alert or dropped.
     */
    async function loadMoreHistory() {
        if (!history) return;
        const { alertId, last } = history;

        try {
            setBusyHistoryMore(true);
            const page = await fetchAlertHistory(alertId, nextPage(last), HISTORY_PAGE_SIZE);
            setHistory(held => (
                held && held.alertId === alertId
                    ? { alertId, rows: appendPage(held.rows, page.items), last: page }
                    : held
            ));
        } catch (e) {
            setHistoryErr({
                alertId,
                failure: describeApiFailure(e, 'alert-history', {
                    retry: () => void loadMoreHistory(),
                }),
            });
        } finally {
            setBusyHistoryMore(false);
        }
    }

    async function decide(kind: FraudDecision) {
        if (!selectedId) return;
        // Held for the whole call. Every landing below is checked against the alert the panel is
        // open on now, because a verdict takes longer than a click on the next card and its answer
        // carries the alert, the payment and the sentence that describes both.
        const id = selectedId;
        try {
            setBusyDecision(true);
            setDecisionErr(null);
            setDecisionMsg(null);
            const updated = await postFraudDecision(id, {
                decision: kind,
                // It rides with all three verdicts and is not the reason for a refusal, which is
                // what the caption over the box now says. Nothing here may narrow it again.
                reason: decisionReason.trim() || undefined,
                // Sent only when the box was edited, and sent exactly as it stands when it was.
                // Echoing back what was read is indistinguishable on the wire from an edit, and an
                // emptied box has to arrive as the empty string, or the column could be written
                // and never cleared. What the two spellings mean is on FraudDecisionRequest.
                notes: notes === notesLoaded ? undefined : notes,
            });
            if (openAlert.current !== id) return;
            setDetail(updated);
            // The box shows what the alert holds, which after a write is what was just sent.
            setNotes(updated.alert.notes ?? '');
            setNotesLoaded(updated.alert.notes ?? '');
            // The reason is emptied, and the notes box is not. They are two different things: the
            // notes are the alert's, they came back on the answer above, and the box goes on
            // showing what is stored. The reason is written per decision, so a line left standing
            // would ride again on the next press, and the next press can be a different verdict on
            // a different alert: the desk annotates, the analyst moves on, and the sentence they
            // wrote about this case is filed as the reason that payment was refused.
            setDecisionReason('');
            // Read off the payment the server sent back, not off the button that was pressed: a
            // DECLINE on a payment that has already gone succeeds and records the verdict without
            // stopping anything, so a sentence keyed on the label would say the money was held
            // when it is gone. The sentence itself is the glossary's, and the customer
            // application's desk reads the same one.
            setDecisionMsg(describeDecision(kind, updated.transfer.status));
            // Keeps the decided alert on screen: with the default NEW filter it leaves the
            // queue the instant it is decided, and clearing the selection would unmount the
            // panel that shows what the decision actually did.
            await reloadList(true);
        } catch (e) {
            // There was no catch here at all. A rejected decision became an unhandled
            // promise rejection: the buttons un-greyed, the stale pre-decision alert stayed
            // on screen, and nothing said the decision had not been applied. Since APPROVE
            // releases a held transfer, an analyst could believe they had approved something
            // that was not approved.
            //
            // Re-read before reporting, because the shared table's sentence for a lost race
            // promises the alert has been reloaded. The queue has a Refresh of its own now, so
            // the promise is no longer the only way an analyst can get a current screen; it is
            // still kept here, since the sentence is about the case they are looking at.
            await reloadList(true);
            try {
                // The alert only. The notes box keeps what the analyst typed, because the write
                // did not happen and this is the one copy of it that is left, and so does the
                // reason box: nothing was filed, so nothing has been spent.
                const current = await fetchAlertDetail(id);
                if (openAlert.current === id) setDetail(current);
            } catch {
                // The alert may no longer be readable; the message does not depend on it.
            }

            if (openAlert.current !== id) return;
            // The four branches written out here disagreed with the four the customer
            // application's desk wrote for the same four codes, and neither of them had a
            // sentence for ALERT_CHANGED, which is exactly what an analyst gets when their
            // verdict loses a race. One table, twenty codes, both desks.
            //
            // No retry on this box, at the shared table's instruction and for the obvious
            // reason: the press that sends a verdict is the one above it.
            setDecisionErr(describeApiFailure(e, 'alert-decision'));
        } finally {
            setBusyDecision(false);
        }
    }

    /**
     * Takes this alert into the name of whoever is signed in, or gives it back to the queue.
     *
     * Two buttons and not a field, which is the whole shape of assignment here: neither route
     * carries a body, the only name either of them can write is the session's, and there is no
     * directory of analysts in this product to hand an alert to. The filter beside the queue is
     * the other half of the same idea, mine against all.
     *
     * Both are open to every analyst, at the server's insistence: an alert held by somebody who
     * has gone home must not be able to hold up the queue.
     */
    async function changeAssignment(take: boolean) {
        if (!selectedId) return;
        const id = selectedId;

        try {
            setBusyAssign(true);
            setAssignErr(null);
            const updated = take ? await takeAlert(id) : await releaseAlert(id);
            if (openAlert.current !== id) return;
            // The notes box is deliberately not refilled from this answer. It carries the stored
            // notes, and taking an alert would then throw away whatever the analyst had typed
            // since opening it, for a press that has nothing to do with the notes.
            setDetail(updated);
            // The queue prints the name at the foot of every card, so the tray is stale until it
            // is re-read. Keeping the selection, or the panel would unmount under the press.
            await reloadList(true);
        } catch (e) {
            if (openAlert.current !== id) return;
            // No retry, for the same reason the decision has none: the two buttons that do this
            // are in the panel, and a control inside the box would be a third way to write a name.
            setAssignErr(describeApiFailure(e, take ? 'alert-assign' : 'alert-release'));
        } finally {
            setBusyAssign(false);
        }
    }

    function setF<K extends keyof AlertFilters>(k: K, v: string) {
        setFilters(prev => ({ ...prev, [k]: v || undefined }));
    }

    /**
     * Reads one amount bound the way the payment form reads its amount, and files it in both
     * places.
     *
     * A bound that cannot be read is not sent: to the endpoint an unsent parameter and a cleared
     * box look the same, so an analyst who mistyped one would be handed the whole queue dressed
     * up as a filtered one. The refusal is held here and shown instead.
     */
    function changeAmountBound(which: AmountBound, typed: string) {
        setAmountText(prev => ({ ...prev, [which]: typed }));

        const key = which === 'min' ? 'minAmount' : 'maxAmount';
        if (typed.trim() === '') {
            setAmountReason(prev => ({ ...prev, [which]: null }));
            setFilters(prev => ({ ...prev, [key]: undefined }));
            return;
        }

        // The endpoint takes a plain decimal, which is what the parser hands back; the Czech
        // spelling of the same number goes into the box on blur, so the analyst sees which
        // reading they got.
        const parsed = parseAmount(typed, readerLocale());
        setAmountReason(prev => ({ ...prev, [which]: parsed.ok ? null : parsed.reason }));
        setFilters(prev => ({ ...prev, [key]: parsed.ok ? String(parsed.value) : undefined }));
    }

    /** Writes the reading back into the box, so a slip shows itself rather than being guessed at. */
    function normalizeAmountBound(which: AmountBound) {
        const parsed = parseAmount(amountText[which], readerLocale());
        if (parsed.ok) {
            setAmountText(prev => ({ ...prev, [which]: parsed.czech }));
        }
    }

    return (
        <div className="shell">
            <div className="window">
                <div className="titlebar">
                    {/*
                      * One product name, qualified by the screen and not by the role: this
                      * window carries the customer screens too once they exist, and the same
                      * screen is named the same way in the customer application.
                      */}
                    <div className="title">MiniBank · Fraud Desk</div>
                    <div className="titlebar-right">
                        <div className="user">{props.username}</div>
                        {/* The action paired with "Sign in" is "Sign out". One product, one verb. */}
                        <button className="btn" onClick={props.onLogout}>Sign out</button>
                    </div>
                </div>

                <div className="content content--split">
                    {/* LEFT: queue */}
                    <div className="left">
                        <div className="panel">
                            <div className="panel-title">Alerts Queue</div>

                            <div className="filters">
                                <div className="row row--2">
                                    <label>{FIELD_LABEL.state}</label>
                                    {/* The values are the server's, the words are the glossary's,
                                        so the option a person picks reads the same as the state
                                        printed on the cards below. */}
                                    <select value={filters.state ?? ''} onChange={(e) => setF('state', e.target.value)}>
                                        <option value="">All</option>
                                        <option value="NEW">{alertStateLabel('NEW')}</option>
                                        <option value="SUSPICIOUS">{alertStateLabel('SUSPICIOUS')}</option>
                                        <option value="OK">{alertStateLabel('OK')}</option>
                                    </select>
                                </div>

                                <div className="row row--3">
                                    <label>{FIELD_LABEL.amount}</label>
                                    {/*
                                      Text and not number: these boxes have to accept the string
                                      the queue beside them prints, and a number input reads only
                                      the browser's own convention. The two words stay short
                                      because at 200 percent text the box is narrower than any
                                      example that would be worth printing; what the boxes accept
                                      is on the cards next to them.
                                    */}
                                    <input
                                        inputMode="decimal"
                                        placeholder="min"
                                        value={amountText.min}
                                        onChange={(e) => changeAmountBound('min', e.target.value)}
                                        onBlur={() => normalizeAmountBound('min')}
                                    />
                                    <input
                                        inputMode="decimal"
                                        placeholder="max"
                                        value={amountText.max}
                                        onChange={(e) => changeAmountBound('max', e.target.value)}
                                        onBlur={() => normalizeAmountBound('max')}
                                    />
                                </div>

                                <div className="row row--2">
                                    <label>{FIELD_LABEL.assignee}</label>
                                    {/*
                                      Two positions, not a name to type. The box here was a text
                                      field that could never match anything: no screen could write
                                      the column, so every name typed into it answered an empty
                                      queue. What can be written is one name, the analyst's own,
                                      so what can be filtered on is the same one, and the option
                                      values are this desk's own rather than the server's because
                                      the query the second one builds is a username.
                                    */}
                                    <select
                                        value={filters.assignee ? 'mine' : ''}
                                        onChange={(e) => {
                                            const mine = e.target.value === 'mine';
                                            setFilters(prev => ({
                                                ...prev,
                                                assignee: mine ? props.username : undefined,
                                            }));
                                        }}
                                    >
                                        <option value="">{ASSIGNED_TO_ANYONE}</option>
                                        <option value="mine">{ASSIGNED_TO_ME}</option>
                                    </select>
                                </div>

                                <div className="row row--2">
                                    <label>Withdrawn</label>
                                    <label>
                                        <input
                                            type="checkbox"
                                            checked={!filters.excludeTransferStatus?.includes(WITHDRAWN)}
                                            /*
                                              Read before the updater runs, not inside it. React
                                              clears currentTarget once the handler returns, and an
                                              updater passed to setState runs later, on the render
                                              pass: reading the event in there dereferenced null and
                                              took the whole screen down with it.
                                            */
                                            onChange={(e) => {
                                                const showWithdrawn = e.currentTarget.checked;
                                                setFilters(prev => ({
                                                    ...prev,
                                                    excludeTransferStatus: showWithdrawn
                                                        ? undefined
                                                        : [WITHDRAWN],
                                                }));
                                            }}
                                        />
                                        {' '}show alerts on cancelled payments
                                    </label>
                                </div>
                            </div>

                            <div className="list">
                                {alerts.map(a => {
                                    const cells = queueCells(a);
                                    return (
                                        <button
                                            key={a.id}
                                            className={'list-item' + (a.id === selectedId ? ' list-item--active' : '')}
                                            onClick={() => openDetail(a.id)}
                                        >
                                            <div className="li-top">
                                                <div className="li-code">{cells.alertCode}</div>
                                                {/* The transfer's status next to the alert's: an
                                                    alert on money that has already gone used to
                                                    look exactly like one on money still held. */}
                                                <div className="li-state">{cells.state} · {cells.transferStatus}</div>
                                            </div>
                                            {/* The two numbers a triage turns on, pushed to the
                                                two edges so they form two columns down the tray. */}
                                            <div className="li-mid">
                                                <span>{cells.transferCode} · {cells.amount}</span>
                                                <span>{cells.riskScore}</span>
                                            </div>
                                            <div className="li-bot">{cells.shortReason}</div>
                                            <div className="li-foot">{cells.createdAt} · {cells.assignee}</div>
                                        </button>
                                    );
                                })}
                                {/*
                                  WHAT THE TRAY HAS TO SAY, and it says exactly one thing.

                                  All three statements are in here, where the rows would be,
                                  because all three are about the same thing: what the queue
                                  holds. The error box used to stand above the tray while the
                                  tray, which knows nothing about it, went on saying "No alerts"
                                  underneath, so a refused filter was announced twice and the
                                  second announcement was wrong: nobody had said there were no
                                  alerts, only that this desk had not been able to ask.

                                  Nothing is said at all while a re-read runs under rows that are
                                  already on screen. Those rows are the queue as it last answered,
                                  the wait belongs to the control that started it, and blanking a
                                  tray an analyst is reading to print "Loading" in the middle of it
                                  is how a refresh loses somebody their place.
                                */}
                                {listErr
                                    ? <ErrorBox failure={listErr} />
                                    : alerts.length === 0 && (
                                        <div className="hint">
                                            {busyList ? QUEUE_LOADING : emptyQueueLine(filters)}
                                        </div>
                                    )}

                                {/*
                                  The last item of the tray, so the way to lengthen the queue is
                                  where the queue runs out. Removed rather than disabled once
                                  everything is on screen: a control that can do nothing still
                                  invites the press that proves it, and the count line under the
                                  tray says so in words instead.
                                */}
                                {hasMore(alerts.length, lastPage?.total ?? 0) && (
                                    <button
                                        type="button"
                                        className="btn list-more"
                                        onClick={() => void loadMore()}
                                        disabled={busyMore}
                                        aria-busy={busyMore || undefined}
                                    >
                                        {busyMore ? SHOW_MORE_BUSY : SHOW_MORE}
                                    </button>
                                )}
                            </div>

                            {/*
                              THE FOOT OF THE QUEUE, and it is drawn at every moment.

                              What is on screen, and the way to ask again. The count says how much
                              of the FILTERED list the tray is holding, so it stands directly under
                              the tray and no longer as the fifth cell of the strip below, where it
                              was the one number on a different basis from the other four and
                              nothing said so. It takes the shared string verbatim, capital S and
                              no full stop, so it reads like the same line at the foot of the
                              customer application's lists.

                              It is not printed over an empty tray: the sentence in the tray has
                              just said the same thing in words, and "Showing: 0" under it is that
                              statement a second time. It is not printed with no page either, which
                              is the state after a refusal: there is nothing to count.

                              The button is what this screen never had. This is the one screen in
                              the product whose contents change on their own, the alerts arrive
                              from the server, and the only ways to see them were to disturb a
                              filter or to reload the window and lose the case under review. It
                              keeps the selection for exactly that reason, and it is beside the
                              counters because they go stale together.
                            */}
                            <div className="queue-line">
                                {lastPage && alerts.length > 0 && (
                                    <div className="hint">
                                        {showingLine(alerts.length, lastPage.total)}
                                    </div>
                                )}
                                <button
                                    type="button"
                                    className="btn"
                                    onClick={() => void reloadList(true)}
                                    disabled={busyList}
                                    aria-busy={busyList || undefined}
                                >
                                    {busyList ? REFRESH_BUSY : REFRESH}
                                </button>
                            </div>

                            {/*
                              These four count the WHOLE queue, not the list above them, and
                              the server means it that way: it counts before applying any
                              filter. That is the right design and the numbers were never
                              wrong - what was missing was the word now in the first cell. New: 7
                              sitting over a list of three reads as a contradiction until the
                              strip says what it is counting.

                              Counting the visible list instead would be worse: the desk opens
                              filtered to new alerts, so two of the three states would be
                              permanently zero, and watching confirmed fraud rise as you work is
                              the whole point of having them.

                              The three states are the only three, so their sum is the queue.
                            */}
                            {counters && (
                                <div className="counters">
                                    <div>
                                        Whole queue, before any filter,{' '}
                                        {counters.newCount + counters.suspiciousCount + counters.okCount}:
                                    </div>
                                    <div>{alertStateLabel('NEW')}: {counters.newCount}</div>
                                    <div>{alertStateLabel('SUSPICIOUS')}: {counters.suspiciousCount}</div>
                                    <div>{alertStateLabel('OK')}: {counters.okCount}</div>
                                </div>
                            )}

                            {/*
                              The one sentence that reconciles the strip above with the tray above
                              it. Under both, because it is about the difference between them, and
                              printed only when there is a difference to explain: an exclusion that
                              is hiding nothing has nothing to say, and a note that is always on
                              screen has stopped being read by the time it matters.

                              A hint and not an error box in either branch. The count is an
                              explanation of the desk's own default, and the second line is that
                              explanation failing to arrive, which is not a failure of the queue
                              that has just answered beside it.
                            */}
                            {hiddenCount > 0 && (
                                <div className="hint queue-note">{hiddenNote(hiddenCount)}</div>
                            )}
                            {hiddenErr && <div className="hint queue-note">{hiddenErr}</div>}
                        </div>
                    </div>

                    {/* RIGHT: detail */}
                    <div className="right">
                        <div className="panel">
                            <div className="panel-title">Alert Detail: Review Suspicious Transaction</div>

                            <div className="panel-scroll">

                                {/*
                                  One statement here too, and the invitation is the last of them.
                                  It used to be drawn on `!selected`, which is not the same
                                  question: the selected id survives a decision and the CARD does
                                  not, since a decided alert leaves the queue the default filter
                                  asks for. So an analyst who had just recorded fraud was invited
                                  to select an alert directly above the alert they had selected,
                                  the whole case still on the pane under the sentence.
                                */}
                                {!detail && !busyDetail && !detailErr && (
                                    <div className="hint">Select an alert on the left.</div>
                                )}

                                {detailErr && <ErrorBox failure={detailErr} />}
                                {busyDetail && <div className="hint">Loading detail…</div>}

                                {detail && !busyDetail && (
                                    <>
                                        {/* The pane's subject line, not a box: what stood here
                                            was a bordered block with no title repeating the card
                                            the analyst had just clicked. The order is the order a
                                            decision is taken in - which case, the two numbers it
                                            turns on, then the two states - and the pane never
                                            named the alert it had open at all. */}
                                        <div className="case-head">
                                            <div className="case-id">
                                                {formatAlertId(detail.alert.id)} · {detail.transfer.code}
                                            </div>
                                            <div className="figures">
                                                <div className="figure">
                                                    <div className="figure-label">{FIELD_LABEL.amount}</div>
                                                    <div className="figure-value">{formatMoney(detail.transfer.amount)}</div>
                                                </div>
                                                <div className="figure">
                                                    <div className="figure-label">{FIELD_LABEL.riskScore}</div>
                                                    <div className="figure-value">
                                                        {detail.alert.riskScore ?? NOT_RECORDED}
                                                    </div>
                                                </div>
                                            </div>
                                            <div className="case-state">
                                                Alert:{' '}
                                                <span className={`tone-${alertStateTone(detail.alert.state)}`}>
                                                    {alertStateLabel(detail.alert.state)}
                                                </span>
                                                {' · '}
                                                Payment:{' '}
                                                <span className={`tone-${transferStatusTone(detail.transfer.status)}`}>
                                                    {transferStatusLabel(detail.transfer.status, 'analyst')}
                                                </span>
                                            </div>
                                        </div>

                                        <div className="box box--facts">
                                            <div className="box-title">Facts</div>
                                            {/*
                                              Two definition lists and not one, split by subject:
                                              the payment on the left, the case on the right. One
                                              list flowed into two columns would interleave the
                                              pairs and put From beside From balance, which is the
                                              pairing an earlier pass deliberately took apart. Two
                                              lists also put the split where a reader will find it,
                                              in the markup, rather than in an nth-child rule.
                                              The wrapper folds back to one column on its own when
                                              the pane is narrow or the text is large.

                                              The two timestamps are named after the objects they
                                              belong to. They used to be "Created" in one block and
                                              "Time" in another, print identically in the demo data,
                                              and neither word said which of the two it meant.
                                            */}
                                            <div className="facts-split">
                                                <dl className="facts">
                                                    <dt>From</dt>
                                                    <dd>{formatIban(detail.transfer.fromIban)}</dd>
                                                    {/* The account number and the balance behind it
                                                        are two facts; they used to share one line. */}
                                                    <dt>From balance</dt>
                                                    <dd className="num">{formatMoney(detail.transfer.fromBalance)}</dd>
                                                    {/* Its own word, not the history column's: this
                                                        pair is the alerted payment's beneficiary,
                                                        and the column below names a route across
                                                        two accounts. The From above it has always
                                                        been a literal for the same reason. */}
                                                    <dt>To</dt>
                                                    {/* A list of facts one to a line is not
                                                        scanned the way a column is, so here both
                                                        readings are worth printing and the one
                                                        the column leaves unsaid gets said. It is
                                                        a line under the number and not a
                                                        parenthesis after it: this list already
                                                        refuses to put two things on one line, and
                                                        the second line is a div so it stands on
                                                        its own with no stylesheet at all. */}
                                                    <dd>
                                                        {formatIban(detail.transfer.toIban)}
                                                        <div className="fact-note">
                                                            {bankBoundaryLabel(
                                                                detail.transfer.toIbanInBank,
                                                            )}
                                                        </div>
                                                    </dd>
                                                    <dt>Fee</dt>
                                                    <dd className="num">{formatMoney(detail.transfer.feeAmount)}</dd>
                                                    <dt>Auth method</dt>
                                                    <dd>{authMethodLabel(detail.transfer.authMethod) || NOT_RECORDED}</dd>
                                                </dl>
                                                <dl className="facts">
                                                    <dt>Payment created</dt>
                                                    <dd>{formatDateTime(detail.transfer.createdAt)}</dd>
                                                    <dt>Alert raised</dt>
                                                    <dd>{formatDateTime(detail.alert.createdAt)}</dd>
                                                    <dt>{FIELD_LABEL.assignee}</dt>
                                                    <dd>{detail.alert.assignee || UNASSIGNED}</dd>
                                                    <dt>{FIELD_LABEL.shortReason}</dt>
                                                    <dd>{detail.alert.reason}</dd>
                                                    {/*
                                                      THE VERDICT OF RECORD, and the two facts that
                                                      make it one: who took it and when.

                                                      Drawn only on an alert that carries one. All
                                                      three are null together until a decision is
                                                      taken, and "Decision: not recorded" on every
                                                      new alert would be three lines saying what the
                                                      alert state at the head of the pane has
                                                      already said. Once there is a verdict it is
                                                      the most important thing on the pane after
                                                      the money, and the pane used to be silent
                                                      about it: an analyst opening a decided alert
                                                      read the queue's own word for the state and
                                                      could not find out who had decided it.

                                                      The analyst's name is the one field of the
                                                      three that can be missing on its own. A
                                                      decision taken from the console has no login
                                                      behind it, so the row says nothing was
                                                      recorded rather than leaving a gap that reads
                                                      as a value withheld.
                                                    */}
                                                    {detail.alert.decision && (
                                                        <>
                                                            <dt>Decision</dt>
                                                            <dd>{decisionLabel(detail.alert.decision)}</dd>
                                                            <dt>Decided by</dt>
                                                            <dd>{detail.alert.decidedBy || NOT_RECORDED}</dd>
                                                            <dt>Resolved</dt>
                                                            <dd>{formatDateTime(detail.alert.resolvedAt)}</dd>
                                                        </>
                                                    )}
                                                </dl>
                                            </div>

                                            {/*
                                              Who holds the case, which is the one fact in the list
                                              above that this screen can change.

                                              Two buttons rather than a field, because the only
                                              name either route can write is the session's: there
                                              is no directory of analysts here and nobody to hand
                                              an alert to. Both are drawn at every moment and
                                              disabled where they would do nothing, unlike the
                                              queue's own Show more, which is removed: this is a
                                              fixed pair in a panel read forty times a day, and a
                                              control that appears and disappears under the pointer
                                              costs more than one that greys.

                                              Taking an alert a colleague holds is allowed, at the
                                              server's insistence, so Take is refused only when the
                                              alert is already in this analyst's name.
                                            */}
                                            <div className="actions">
                                                <button
                                                    type="button"
                                                    className="btn"
                                                    disabled={
                                                        busyAssign
                                                        || detail.alert.assignee === props.username
                                                    }
                                                    aria-busy={busyAssign || undefined}
                                                    onClick={() => void changeAssignment(true)}
                                                >{TAKE_ALERT}</button>
                                                <button
                                                    type="button"
                                                    className="btn"
                                                    disabled={busyAssign || !detail.alert.assignee}
                                                    aria-busy={busyAssign || undefined}
                                                    onClick={() => void changeAssignment(false)}
                                                >{RELEASE_ALERT}</button>
                                            </div>

                                            {assignErr && <ErrorBox failure={assignErr} />}
                                        </div>

                                        {/*
                                          THE CUSTOMER'S PAYMENTS, read from their own route.

                                          `detail.history` is no longer drawn. It carries ten rows
                                          and says nothing about how many there are, so the panel
                                          could offer no more and could not even say what it was
                                          showing ten of; the only mention of the cut was the word
                                          in the title. The route beside it answers a page and a
                                          total, so the foot of the table can state both.

                                          The rows are matched to the alert they were fetched for
                                          before they are drawn. Two requests are now in flight for
                                          one panel and this is the slower one; nothing on a
                                          payment row says whose it is, so a page that arrives
                                          after the analyst has moved on must not be laid under
                                          another customer's facts.
                                        */}
                                        <div className="box box--history">
                                            <div className="box-title">{CUSTOMER_HISTORY_TITLE}</div>
                                            {shownHistoryErr && <ErrorBox failure={shownHistoryErr} />}
                                            {busyHistory && <div className="hint">Loading payments…</div>}
                                            {shownHistory && (shownHistory.rows.length === 0 ? (
                                                <div className="hint">No history</div>
                                            ) : (
                                                <table className="history">
                                                    <colgroup>
                                                        {HISTORY_COLUMN_FIELDS.map(f => (
                                                            <col
                                                                key={f}
                                                                className={HISTORY_COLUMN_CLASS[f]?.col}
                                                            />
                                                        ))}
                                                    </colgroup>
                                                    <thead>
                                                        <tr>
                                                            {HISTORY_COLUMN_FIELDS.map(f => (
                                                                <th
                                                                    key={f}
                                                                    scope="col"
                                                                    className={HISTORY_COLUMN_CLASS[f]?.cell}
                                                                >
                                                                    {HISTORY_FIELD_LABEL[f]}
                                                                </th>
                                                            ))}
                                                        </tr>
                                                    </thead>
                                                    {shownHistory.rows.map((h) => {
                                                        // Nothing stands above the first row, so
                                                        // it prints its account; every row after
                                                        // it is compared with the one before.
                                                        const cells = historyCells(
                                                            h,
                                                            detail.transfer.fromIban,
                                                        );
                                                        return (
                                                            /* One row group per payment, so the
                                                               reason a payment was refused stays
                                                               part of the row it explains. */
                                                            <tbody key={h.id}>
                                                                <tr>
                                                                    {HISTORY_COLUMN_FIELDS.map(f => (
                                                                        <td
                                                                            key={f}
                                                                            className={HISTORY_COLUMN_CLASS[f]?.cell}
                                                                        >
                                                                            {cells[f]}
                                                                        </td>
                                                                    ))}
                                                                </tr>
                                                                {h.declineReason && (
                                                                    <tr className="history-note">
                                                                        <td colSpan={HISTORY_COLUMN_FIELDS.length}>
                                                                            {FIELD_LABEL[HISTORY_NOTE_FIELD]}:{' '}
                                                                            {cells[HISTORY_NOTE_FIELD]}
                                                                        </td>
                                                                    </tr>
                                                                )}
                                                            </tbody>
                                                        );
                                                    })}
                                                </table>
                                            ))}

                                            {/*
                                              How many of the customer's payments there are, and
                                              the way to see the rest of them.

                                              The count line is the shared one, word for word, so
                                              the foot of this table reads like the foot of the
                                              queue beside it and like the same line in the
                                              customer application. The button is removed rather
                                              than disabled once every payment is on screen, which
                                              is the rule the queue's own Show more follows: there
                                              the count line says in words that the list is whole.
                                            */}
                                            {shownHistory && (
                                                <div className="box-foot">
                                                    <div className="hint">
                                                        {showingLine(
                                                            shownHistory.rows.length,
                                                            shownHistory.last.total,
                                                        )}
                                                    </div>
                                                    {hasMore(
                                                        shownHistory.rows.length,
                                                        shownHistory.last.total,
                                                    ) && (
                                                        <button
                                                            type="button"
                                                            className="btn"
                                                            onClick={() => void loadMoreHistory()}
                                                            disabled={busyHistoryMore}
                                                            aria-busy={busyHistoryMore || undefined}
                                                        >
                                                            {busyHistoryMore ? SHOW_MORE_BUSY : SHOW_MORE}
                                                        </button>
                                                    )}
                                                </div>
                                            )}
                                        </div>
                                    </>
                                )}

                            </div>

                            {/*
                              Outside the scroller on purpose. The verdict is why this screen
                              exists and it was the one thing below the fold at every size but
                              2560, so it is the panel's footer and the evidence scrolls past it.
                              The condition is written a second time rather than widened: the
                              wrapper above is the scroller, and the footer has to be its sibling.
                            */}
                            {detail && !busyDetail && (
                                <div className="panel-foot">
                                    <div className="box-title">Decision</div>
                                    {/*
                                      The caption is the shared one, and it says "this decision"
                                      rather than "the reason": the comment rides with all three
                                      presses, so a word about declining would tell an analyst
                                      clearing an alert that what they wrote belongs to a refusal
                                      they are not making. The other desk carries the same string.
                                    */}
                                    <div className="row row--2">
                                        <label>{DECISION_REASON_LABEL}</label>
                                        <input value={decisionReason} onChange={(e) => setDecisionReason(e.target.value)} placeholder="optional" />
                                    </div>
                                    {/*
                                      The alert's notes, not a blank box beside them. It opens
                                      holding whatever is stored, so a colleague's paragraph is
                                      read before it is written over rather than replaced by the
                                      first line typed into an empty field, and it is a textarea
                                      because what it now holds is somebody's paragraph.
                                    */}
                                    <div className="row row--2">
                                        <label>Notes</label>
                                        <textarea rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="internal notes" />
                                    </div>

                                    {decisionErr && <ErrorBox failure={decisionErr} />}
                                    {decisionMsg && <div className="result">{decisionMsg}</div>}

                                    {/* The buttons mirror the domain guards exactly, so
                                        a click the server would refuse - taking the
                                        typed notes down with it - is not reachable.
                                        Approve only from a new alert; Decline from
                                        anything not already recorded as fraud, which is
                                        what lets fraud confirmed after the money left be
                                        recorded on an alert that had already been cleared.

                                        The words are the glossary's. This desk said
                                        "release to customer" and "record fraud" where the
                                        customer application's desk said "release to the
                                        customer" and "record confirmed fraud", so one
                                        analyst doing one job read two labels for the same
                                        press. */}
                                    <div className="actions">
                                        <button
                                            className="btn btn--primary"
                                            disabled={busyDecision || detail.alert.state !== 'NEW'}
                                            onClick={() => decide('APPROVE')}
                                        >{decisionActionLabel('APPROVE')}</button>
                                        <button
                                            className="btn btn--danger"
                                            disabled={busyDecision || detail.alert.state === 'SUSPICIOUS'}
                                            onClick={() => decide('DECLINE')}
                                        >{decisionActionLabel('DECLINE')}</button>
                                        {/* The token this posts was REQUEST_CONFIRMATION, which
                                            named something it has never done: it asks nobody for
                                            anything and takes no decision. */}
                                        <button
                                            className="btn btn--quiet"
                                            disabled={busyDecision}
                                            onClick={() => decide('ANNOTATE')}
                                        >{decisionActionLabel('ANNOTATE')}</button>
                                    </div>

                                    <div className="hint">
                                        Approving does not send the money: it releases the
                                        payment for the customer to confirm. Declining a
                                        payment that has already been sent records the
                                        verdict; it does not reverse it. The notes box holds
                                        what is stored on the alert, so whatever is left in it
                                        is what any of the three buttons saves.
                                    </div>
                                </div>
                            )}
                        </div>
                    </div>

                </div>
            </div>
        </div>
    );
}
