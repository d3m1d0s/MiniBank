// src/FraudDeskPage.tsx

import { useEffect, useRef, useState, type ReactNode } from 'react';
import './App.css';
import {
    fetchAlerts,
    fetchAlertDetail,
    fetchAlertHistory,
    fetchHiddenAlerts,
    postFraudDecision,
    releaseAlert,
    takeAlert,
    type AlertQueueItem,
    type AlertDetail,
    type AlertFilters,
    type AlertNote,
    type FraudDecision,
    type FraudDecisionRequest,
    type AlertCounters,
    type HistoryItem,
    type Page,
} from './api';
import { amountRangeProblem } from '@shared/alertFilters';
import { formatMoney, readerLocale } from './money';
import { formatFeeLine, parseAmount } from '@shared/money';
import ErrorBox from './ErrorBox';
import { describeApiError, describeApiFailure, type ApiFailure } from '@shared/apiErrors';
import {
    ALERTS_QUEUE_TITLE,
    ALERT_CARD_TITLE,
    ALERT_DETAILS_TITLE,
    ALERT_DETAIL_LOADING,
    ALERT_NOTES_TITLE,
    ALERT_REASON_LABEL,
    ASSIGNED_TO_ANYONE,
    ASSIGNED_TO_ME,
    CUSTOMER_HISTORY_TITLE,
    DECISION_BUSY,
    DECISION_COMMENT_LABEL,
    DECISION_COMMENT_PLACEHOLDER,
    DECLINE_ALREADY_SENT,
    DECLINE_NEEDS_COMMENT,
    DECISION_NOTE_LABEL,
    DECISION_NOTE_PLACEHOLDER,
    DECISION_RESULT_TITLE,
    DECISION_TITLE,
    NO_ALERT_NOTES,
    NO_HISTORY,
    PAYMENT_CARD_TITLE,
    QUEUE_LOADING,
    REFRESH,
    REFRESH_BUSY,
    RELEASE_ALERT,
    SELECT_ALERT,
    SELECT_ALERT_TO_DECIDE,
    SHOW_WITHDRAWN_ALERTS,
    TAKE_ALERT,
    UNASSIGNED,
    alertStateLabel,
    alertStateTone,
    authMethodText,
    bankBoundaryMark,
    bankBoundaryLabel,
    decisionActionLabel,
    decisionLabel,
    describeDeclineReason,
    describeDecision,
    dispatchStateLabel,
    emptyQueueNote,
    hiddenAlertsNote,
    noteAuthorLabel,
    MAX_DECISION_COMMENT,
    MAX_DECISION_NOTE,
    queueCountersSentence,
    TIMES_ZONE_NOTE,
    transferStatusLabel,
    transferStatusTone,
} from '@shared/glossary';
import {
    EMPTY_VALUE,
    formatAlertId,
    formatDateTime,
    formatIban,
    formatTransferId,
    NOT_RECORDED,
} from '@shared/format';
import {
    ALERT_NOTE_FIELDS,
    ALERT_NOTE_LABEL,
    ALERT_QUEUE_FIELDS,
    AMOUNT_FROM_LABEL,
    AMOUNT_PLACEHOLDER,
    AMOUNT_TO_LABEL,
    FIELD_LABEL,
    HISTORY_FIELD_LABEL,
    HISTORY_COLUMN_FIELDS,
    HISTORY_DECLINED_FIELD,
    HISTORY_SETTLED_FIELD,
    HISTORY_UNDER_ROW_FIELDS,
    TRANSFER_DETAIL_LABEL,
    type AlertNoteField,
    type AlertNoteRowCells,
    type AlertQueueField,
    type HistoryField,
    type HistoryRowCells,
    type QueueRowCells,
} from '@shared/fields';
import {
    SHOW_MORE,
    SHOW_MORE_BUSY,
    appendPage,
    hasMore,
    nextPage,
    showingLine,
} from '@shared/paging';
import Nav from './Nav';
import TableFrame from './TableFrame';
import { revealChoice } from './reveal';
import type { NavRole, NavView } from '@shared/navigation';

/** The transfer status a withdrawn payment ends in. */
const WITHDRAWN = 'DECLINED';

/**
 * What a table of payments says while its first page is on its way.
 *
 * The same words on all three screens that draw one, and a literal on all three today: this desk,
 * the customer's own history and the workstation's panel. It is the shape a word is in just before
 * it drifts, and the shared glossary is where it belongs; see the note beside this run for the
 * constant that has been asked for. It is written out identically meanwhile rather than shortened
 * here, because two of the three wordings differing is the fault, not the third one existing.
 */
const PAYMENTS_LOADING = 'Loading payments…';

/* The sentences the two amount boxes point at with aria-describedby. The range one is named
   separately because it belongs to both boxes and is written once. */
const AMOUNT_MIN_ERROR_ID = 'filter-amount-min-error';
const AMOUNT_MAX_ERROR_ID = 'filter-amount-max-error';
const AMOUNT_RANGE_ERROR_ID = 'filter-amount-range-error';

/**
 * Which amount bound the analyst is typing in. The two boxes are one control by role, so they
 * are handled by one function and told apart by this.
 */
type AmountBound = 'min' | 'max';

/*
 * The desk takes a navigation callback like the other two screens, although App still keeps the
 * analyst on this one view. The column is drawn from the same list everywhere, so the entry a
 * person is standing on stays a button here as well, and the caller decides what pressing it does.
 */
interface Props {
    role: NavRole;
    /**
     * The signed-in analyst's login, which is the string the assignee column holds.
     *
     * The queue's assignee filter is a match against that column, so the Mine position of the
     * filter is this value and nothing else. It is deliberately the login and not the person's
     * name: the assignment route writes the session's username, so a name would match nothing.
     */
    username: string;
    /* The mark and the name of the application, built by App and rendered here as it arrives. */
    brand?: ReactNode;
    /* Who is signed in and the way out, built by App and rendered here as it arrives. */
    identity?: ReactNode;
    onNavigate: (view: NavView) => void;
}

/**
 * How many alerts arrive at a time.
 *
 * The server's own default. A queue is meant to be seen entire, and the analyst's question at its
 * foot is how much work is left rather than whether to read on, which is why the count line is
 * beside the button and the counters above the list are a different number entirely.
 */
const PAGE_SIZE = 25;

/**
 * How many payments of the customer's history arrive at a time.
 *
 * Ten, which is what the alert detail already carries inside it, so the first page of the paged
 * route is the same ten rows and pressing nothing changes nothing. The heading over the table says
 * ten as well; see the sentence beside the count line for what it now says instead.
 */
const HISTORY_PAGE_SIZE = 10;

/**
 * Which queue columns are more than left-aligned text.
 *
 * Marked where the cell is built rather than found by counting header cells in the stylesheet,
 * which matches a fixed number of columns and breaks silently the day one is added. The
 * workstation already does it this way.
 */
const QUEUE_CELL_CLASS: Partial<Record<AlertQueueField, string>> = {
    amount: 'cell--amount',
    // The three that open a record at the width where the nine columns give way and each row is
    // drawn as one. They are what an analyst picks an alert by: which alert, how much money, and
    // what state the two things are in. The classes do nothing while the queue is a table.
    alertCode: 'cell--lead',
    state: 'cell--state',
    transferStatus: 'cell--state',
};

const HISTORY_CELL_CLASS: Partial<Record<HistoryField, string>> = {
    amount: 'cell--amount',
};

/**
 * The two journal columns that are told anything about their width, and the third one is not.
 *
 * The entry takes whatever is left, so the words never set the ruling; the timestamp beside it is
 * held on one line, which is not the default once the prose column has claimed the rest. Squeezed
 * to its content, `21. 8. 2026 17:28` broke over three lines in a column meant to be scanned down.
 * The author is left alone: it holds a login, and a long one wrapping costs nothing.
 */
const NOTE_CELL_CLASS: Partial<Record<AlertNoteField, string>> = {
    writtenAt: 'cell--stamp',
    text: 'cell--prose',
};

/**
 * A queue entry's nine fields, ready to be laid out.
 *
 * Built through the shared row type so a field the server sends and this desk forgets is a build
 * failure rather than something an analyst discovers is missing. The workstation reads a queue
 * entry down a card and this one reads it across a table, so each builds its own cells and only
 * the field set and the words are shared.
 *
 * One cell differs from the workstation's on purpose, and one that used to differ no longer does.
 * A column has a heading, so a risk score the rules never wrote is the data table's dash here,
 * where the card, having no headings, has to say `Risk 80` in words.
 *
 * The assignee is not that case. `null` there is not a value the record is missing, it is the
 * state of an alert nobody has taken, and it has a word. The dash was saying "nothing to report"
 * about a fact worth reporting, and it said it 153 pixels above the panel below, which named the
 * same field of the same alert `Assignee: unassigned` in the same first screen. The journal beside
 * it already spells an author it never learned rather than leaving that column blank.
 */
function queueCells(a: AlertQueueItem): QueueRowCells<ReactNode> {
    return {
        alertCode: a.alertCode,
        transferCode: a.transferCode,
        state: (
            <span className={`tone-${alertStateTone(a.state)}`}>{alertStateLabel(a.state)}</span>
        ),
        transferStatus: (
            <span className={`tone-${transferStatusTone(a.transferStatus)}`}>
                {transferStatusLabel(a.transferStatus, 'analyst')}
            </span>
        ),
        amount: formatMoney(a.amount),
        // The rules join what they found with a plus, so an alert that tripped four of them writes
        // a sentence into a column sized for a phrase. Two lines here and the whole of it on the
        // cell, which is also what the panel below prints in full the moment the row is chosen.
        shortReason: (
            <span className="reason-short" title={a.shortReason}>
                {a.shortReason}
            </span>
        ),
        riskScore: a.riskScore ?? EMPTY_VALUE,
        assignee: a.assignee || UNASSIGNED,
        createdAt: formatDateTime(a.createdAt),
    };
}

/**
 * One row of the payment history beside an alert.
 *
 * The decline reason no longer keeps a column. It is prose, and prose has no width to be given: a
 * sixth column was paid for out of the route cell, which is the tightest one this table has, to
 * hold a sentence somebody typed. It goes under the payment it explains, which is the form the
 * workstation worked out first and the one the shared field list now names for both platforms.
 *
 * `alertedIban` is the account the alert was raised on, which the panel of facts above names. The
 * row that left it carries the ink and the weight; the rest step back, so a payment out of the
 * customer's OTHER account is the one the eye lands on. A sum split across two of one's own
 * accounts so each half stays under a threshold is exactly what this table exists to make visible.
 */
function historyCells(h: HistoryItem, alertedIban: string | null): HistoryRowCells<ReactNode> {
    const alerted = alertedIban !== null && h.fromIban === alertedIban;
    const boundary = bankBoundaryMark(h.toIbanInBank);
    const feeLine = formatFeeLine(h.fee);
    // Tested for a value rather than against null, and the difference showed on the screen: an
    // API that predates the column sends no key at all, and `=== null` let `undefined` through to
    // formatDateTime, which answers an absence with the table's dash. Every row then carried a
    // second line carrying EMPTY_VALUE, which is the one thing this line must never say: it would
    // claim the settlement is known and withheld.
    const settled = h.settledAt ? formatDateTime(h.settledAt) : null;
    const declined = h.declinedAt ? formatDateTime(h.declinedAt) : null;

    // Whichever end this payment came to; see the customer's own history for why it is written as
    // a preference rather than an either-or. On a fraud desk the refused end is the one that fills
    // the table, so this line stops being the exception it was when only settlements could fill it.
    const ended = settled ?? declined;
    const endedField = settled ? HISTORY_SETTLED_FIELD : HISTORY_DECLINED_FIELD;

    return {
        id: formatTransferId(h.id),
        // When it was asked for, and under it when it ended: the money moved, or the bank stopped
        // it. Until the second of those could be printed, a stopped payment showed one timestamp
        // and an analyst comparing it with the rows around it had nothing to compare.
        createdAt: (
            <>
                <span className="time-asked">{formatDateTime(h.createdAt)}</span>
                {ended && (
                    <span className="time-settled">
                        <span className="visually-hidden">
                            {FIELD_LABEL[endedField]}:{' '}
                        </span>
                        {ended}
                    </span>
                )}
            </>
        ),
        settledAt: settled,
        declinedAt: declined,
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
        // Where the money left, over where it went. The mark on the top line is ink and weight and
        // nothing else, so the row that left the account under review steps forward. The word on
        // the bottom line appears only where the money never left the bank, which on this desk is
        // the row an analyst has something left to do about.
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
        // The reference the payer typed on the form, which on this desk is evidence rather than
        // decoration: it is the one thing on the row the customer wrote themselves.
        message: h.message,
        // The same sentence the customer is now shown for their own declined payment, from the
        // same function. No absent value: a payment that was not declined has no note row at all.
        declineReason: describeDeclineReason(h.declineReason),
    };
}

/**
 * One entry of the alert's journal.
 *
 * Three cells and no absent value among them. The server refuses an entry with no timestamp and one
 * with no text, and the only field that can arrive empty is the author, which is answered with a
 * word rather than with the table's dash: a blank in a column of names reads as a name withheld,
 * and the one entry that can carry it is the line the migration brought over from the single notes
 * column, which kept the text and never kept a name.
 */
function noteCells(n: AlertNote): AlertNoteRowCells<ReactNode> {
    return {
        writtenAt: formatDateTime(n.writtenAt),
        author: noteAuthorLabel(n.author),
        text: n.text,
    };
}

export default function FraudDeskPage({ role, username, brand, identity, onNavigate }: Props) {
    const [alerts, setAlerts] = useState<AlertQueueItem[]>([]);

    /**
     * The page envelope as the queue last answered, so the next page is asked for by the page
     * that arrived rather than by the rows on screen; those disagree the moment a row that has
     * already been seen is dropped. Its `total` is what the current filters match and is NOT the
     * counters beside it.
     */
    const [lastPage, setLastPage] = useState<Page<AlertQueueItem> | null>(null);
    const [counters, setCounters] = useState<AlertCounters | null>(null);
    const [selectedId, setSelectedId] = useState<number | null>(null);
    const [detail, setDetail] = useState<AlertDetail | null>(null);

    /**
     * Which alert is open, readable from inside a request that started before it.
     *
     * The state is not: a handler closes over the value it had when it ran, so an answer that
     * arrives after the analyst has moved on has no way to tell. Held for the history, whose
     * answer names no alert of its own.
     */
    const selectedRef = useRef<number | null>(null);

    /**
     * The panel a choice fills in, so that a choice can be answered where the analyst is looking.
     *
     * Stacked in one column this panel begins below the filters, the counters and the whole queue,
     * and the decision box below it begins at 1939px of a 2544px page: a press on a row moved
     * nothing that was on screen. See revealChoice, which leaves the page alone on the layouts
     * where the panel is already in the window.
     */
    const detailPanelRef = useRef<HTMLElement | null>(null);

    /**
     * The desk hides withdrawn payments by default; the endpoint hides nothing by default.
     *
     * An alert whose payment the customer cancelled has nothing left to decide - the money is
     * not going anywhere - but it is still evidence and nothing has resolved it, so it stays
     * New and used to sit in this queue forever. Hidden here rather than resolved anywhere: the
     * alert's state is the analyst's verdict and no screen may write it.
     *
     * The same shape as the state filter: the server has no default, the page has one, and the
     * control that undoes it is on screen.
     */
    const [filters, setFilters] = useState<AlertFilters>({
        state: 'NEW',
        excludeTransferStatus: [WITHDRAWN],
    });

    /**
     * The two amount bounds as they were typed, and what is wrong with each of them.
     *
     * The boxes hold text, not a number. They were type="number", which refuses the very string
     * the queue prints above them - an analyst who copies `10 001,00` out of a row and pastes it
     * here got an empty box - and one amount convention across both applications is the point:
     * the same reader, the same parser and the same echo as the payment form.
     *
     * The reason is kept per box, because fixing the upper bound must not silently forgive the
     * lower one, and because it names what is wrong with the string rather than that something
     * was.
     */
    const [amountText, setAmountText] = useState({ min: '', max: '' });
    const [amountReason, setAmountReason] = useState<Record<AmountBound, string | null>>({
        min: null,
        max: null,
    });

    /**
     * How many alerts the payment-status exclusion is keeping off the screen right now.
     *
     * Read from the other half of the same filter rather than worked out here: the queue and the
     * hidden route are sent one query string, so the two answers are the two halves of it and
     * their totals add up. Zero when nothing is being hidden, which is also what the route
     * answers for a caller that excludes nothing.
     */
    const [hiddenTotal, setHiddenTotal] = useState(0);

    /**
     * Why the number that reconciles the list with the counters is not there.
     *
     * Said in the hint's own voice and not in an error box, which is the workstation's shape for
     * this and the right one: nobody asked for that number, it is the desk explaining its own
     * filter, and a failure to explain must not read as a failure of the queue standing beside
     * it, which has just answered. Swallowed here until now, so the contradiction this sentence
     * exists to resolve was simply left standing with nothing to account for it.
     */
    const [hiddenError, setHiddenError] = useState<string | null>(null);

    /**
     * The customer's payments beside the alert, and the page envelope they arrived in.
     *
     * Held apart from `detail` because the two have different lifetimes. The detail carries ten
     * rows and no count, so a panel reading it alone can never tell a customer with ten payments
     * from one with two hundred; this comes from the route beside it, which counts them and pages
     * them, and turning a page here does not make the server rebuild the alert.
     */
    const [history, setHistory] = useState<HistoryItem[]>([]);
    const [historyPage, setHistoryPage] = useState<Page<HistoryItem> | null>(null);

    /**
     * Whether the counted first page of the customer's payments is on its way.
     *
     * The table has rows before it arrives, from the alert, so this does not stand in for them.
     * What it stands in for is the count and the control under them, which is exactly what the
     * panel could not say anything about while the request was out.
     */
    const [loadingHistoryFirst, setLoadingHistoryFirst] = useState(false);
    const [loadingHistoryMore, setLoadingHistoryMore] = useState(false);

    const [listError, setListError] = useState<ApiFailure | null>(null);
    const [detailError, setDetailError] = useState<ApiFailure | null>(null);

    /**
     * The failure of a further page of the customer's payments, which is not the failure of the
     * alert.
     *
     * It used to share detailError and therefore stood at the top of the panel, above an alert
     * that had loaded perfectly well, saying that the history could not be read while ten rows of
     * it were on the screen. It belongs beside the button that asked for the eleventh.
     */
    const [historyError, setHistoryError] = useState<ApiFailure | null>(null);
    const [decisionError, setDecisionError] = useState<ApiFailure | null>(null);
    const [decisionMessage, setDecisionMessage] = useState<string | null>(null);
    const [assignError, setAssignError] = useState<ApiFailure | null>(null);

    /**
     * Whether a first page of the queue is in flight, and it starts true.
     *
     * It started false, and the effect that fires the request runs after the first paint, so the
     * desk opened by stating "No fraud alerts." over a queue the server was about to answer with.
     * The rows already on screen outrank it below, so a reload after a decision does not blank a
     * queue the analyst is reading.
     */
    const [loadingList, setLoadingList] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);
    const [loadingDetail, setLoadingDetail] = useState(false);
    const [loadingDecision, setLoadingDecision] = useState(false);
    const [loadingAssignment, setLoadingAssignment] = useState(false);
    const [refreshing, setRefreshing] = useState(false);

    /**
     * Which of the three decision buttons is in flight, or null.
     *
     * All three are disabled while any one of them works, because the server takes one verdict per
     * alert and a second press is a refusal that would carry what was typed down with it. Only
     * the pressed one changes its word: `Applying…` used to sit on Approve whichever button was
     * pressed, so declining a payment made Approve announce the work.
     */
    const [pressed, setPressed] = useState<FraudDecision | null>(null);

    /**
     * The analyst's comment on the verdict, and the one entry they are adding to the journal.
     *
     * NEITHER IS SEEDED FROM THE ALERT, and the second one is why the pair used to need a third
     * piece of state beside them. The notes box was a copy of a column that every press overwrote,
     * so it had to be filled with what was stored and compared against it to work out whether the
     * analyst had touched it. The column is a journal now: this box holds one entry to append, so
     * an empty box is simply nothing to add and there is nothing to compare it with.
     */
    const [decisionComment, setDecisionComment] = useState('');
    const [decisionNote, setDecisionNote] = useState('');

    useEffect(() => {
        void loadAlerts();
        // amountReason belongs in here beside filters. Typing something the parser cannot read
        // into an already empty box leaves the filters untouched, so on filters alone nothing
        // would re-run and the analyst would be told nothing at all.
        //
        // The directive has to be the line directly above the closing one, and it was three lines
        // above it, disabling a comment: the linter reported both the rule it was meant to silence
        // and the directive that silenced nothing.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [filters, amountReason]);

    /**
     * Reads one amount bound the way the payment form reads its amount, and echoes it back.
     *
     * A bound that cannot be read is not sent: an unsent parameter and a cleared box look the
     * same to the endpoint, and an analyst who mistyped a bound would be handed the whole queue
     * looking like a filtered one. So the refusal is held here and shown instead.
     */
    function changeAmountBound(which: AmountBound, raw: string) {
        setAmountText((prev) => ({ ...prev, [which]: raw }));

        const key = which === 'min' ? 'minAmount' : 'maxAmount';
        if (raw.trim() === '') {
            setAmountReason((prev) => ({ ...prev, [which]: null }));
            setFilters((prev) => ({ ...prev, [key]: undefined }));
            return;
        }

        // The endpoint takes a plain decimal, which is what the parser hands back; the Czech
        // spelling of the same number goes into the box on blur, so the analyst sees which
        // reading they got.
        const parsed = parseAmount(raw, readerLocale());
        setAmountReason((prev) => ({ ...prev, [which]: parsed.ok ? null : parsed.reason }));
        setFilters((prev) => ({ ...prev, [key]: parsed.ok ? String(parsed.value) : undefined }));
    }

    function normalizeAmountBound(which: AmountBound) {
        const parsed = parseAmount(amountText[which], readerLocale());
        if (parsed.ok) {
            setAmountText((prev) => ({ ...prev, [which]: parsed.czech }));
        }
    }

    /**
     * The two numbers being the wrong way round, which is a fault of neither box on its own.
     *
     * Asked only once both bounds are readable, in the order loadAlerts asks them in: a box the
     * parser refused has no number in the filters at all, so a range read across it would be a
     * range read across one bound.
     */
    const rangeProblem =
        amountReason.min == null && amountReason.max == null
            ? amountRangeProblem(filters, { min: false, max: false })
            : null;

    /** Which sentences one amount box is answered by, its own and the one about the pair. */
    function describeAmountBox(which: AmountBound): string | undefined {
        const ownId = which === 'min' ? AMOUNT_MIN_ERROR_ID : AMOUNT_MAX_ERROR_ID;
        const said = [
            amountReason[which] ? ownId : null,
            rangeProblem ? AMOUNT_RANGE_ERROR_ID : null,
        ].filter((id): id is string => id !== null);
        return said.length > 0 ? said.join(' ') : undefined;
    }

    async function loadAlerts(keepSelection = false) {
        // Checked before the request, and the server checks it again. This half exists to name
        // which two numbers are the wrong way round; the server cannot, because no handler
        // echoes an exception message. The server half exists because the endpoint is reachable
        // without this screen.
        //
        // A box the parser refused is answered with the parser's own sentence, which says what
        // is wrong with that string. The shared guard's unreadable branch is the answer for a
        // caller that has no sentence of its own, so by the time it runs both bounds are either
        // readable or empty.
        const problem =
            amountReason.min ??
            amountReason.max ??
            amountRangeProblem(filters, { min: false, max: false });
        if (problem) {
            setAlerts([]);
            setLastPage(null);
            setHiddenTotal(0);
            setLoadingList(false);
            // Not an answer from the bank, so there is no status to print and nothing to ask
            // again: the way out is the box the analyst typed into, and the sentence about what
            // is wrong with it now stands at that box. This one says why the rows are gone.
            // The counters are deliberately NOT cleared. They count the whole queue before any
            // filter, so a filter this desk refused to send cannot have changed them, and the
            // caption is the one thing on the screen that still holds. It used to go with the
            // list, which left the section with a heading, an error and no bottom to it.
            return;
        }

        try {
            setLoadingList(true);
            setListError(null);
            // From the top, and as wide as what is already on screen: a decision reloads this
            // list, and a queue that collapsed back to its first page every time an alert was
            // decided would lose the rows the analyst had opened out to reach it.
            const wanted = keepSelection
                ? Math.max(PAGE_SIZE, alerts.length)
                : PAGE_SIZE;
            const resp = await fetchAlerts(filters, 0, wanted);
            setAlerts(resp.alerts.items);
            setLastPage(resp.alerts);
            setCounters(resp.counters);
            // The other half of the same filter, asked with the same query string, so the number
            // that reconciles an empty list with the counters arrives beside the list rather than
            // a press later. Not awaited: nothing on the queue waits for it.
            void loadHiddenTotal();

            // If the currently selected alert disappeared from the list, reset selection and details
            if (!keepSelection && selectedId && !resp.alerts.items.some((a) => a.id === selectedId)) {
                setSelectedId(null);
                setDetail(null);
            }
        } catch (e) {
            setListError(
                describeApiFailure(e, 'alert-queue', {
                    retry: () => void loadAlerts(keepSelection),
                }),
            );
        } finally {
            setLoadingList(false);
        }
    }

    /**
     * Re-reads the queue on demand.
     *
     * An alert is raised by a payment somebody else makes, so work arrives on this desk without
     * anything on this screen having happened, and nothing here polls: the queue was read on
     * mount, after a decision and after an assignment. The only way to see new work was to
     * disturb a filter, which is a request that also changes what is on the screen.
     *
     * The queue and nothing else. It deliberately does not re-read the open alert: a re-read
     * redraws the panel under an analyst who is halfway through reading it, and the same rule is
     * why the assignment call touches neither of the two boxes.
     */
    async function handleRefresh() {
        try {
            setRefreshing(true);
            await loadAlerts(true);
        } finally {
            setRefreshing(false);
        }
    }

    /**
     * The next page of the queue, appended under the rows already there.
     *
     * The list's own "Loading alerts…" hint is not shown for this: that sentence means the first
     * page, and drawing it would blank a queue the analyst is reading. The button says so instead
     * and keeps its place while it does.
     */
    async function loadMoreAlerts() {
        if (!lastPage) return;

        try {
            setLoadingMore(true);
            setListError(null);
            const resp = await fetchAlerts(filters, nextPage(lastPage), PAGE_SIZE);
            setAlerts((held) => appendPage(held, resp.alerts.items));
            setLastPage(resp.alerts);
            setCounters(resp.counters);
        } catch (e) {
            // The retry asks again for the page that failed, not for the first one.
            setListError(
                describeApiFailure(e, 'alert-queue', { retry: () => void loadMoreAlerts() }),
            );
        } finally {
            setLoadingMore(false);
        }
    }

    /**
     * How many alerts this desk's own exclusion is keeping off the list.
     *
     * Asked for one row, because the total is the whole answer: this reconciles two numbers and
     * lists nothing. A caller that excludes nothing is hiding nothing, and the route says so
     * without touching the store, but the request is skipped here anyway.
     */
    async function loadHiddenTotal() {
        if (!filters.excludeTransferStatus?.length) {
            setHiddenTotal(0);
            setHiddenError(null);
            return;
        }

        try {
            const hidden = await fetchHiddenAlerts(filters, 0, 1);
            setHiddenTotal(hidden.total);
            setHiddenError(null);
        } catch (e) {
            // Not an error box: this is the desk explaining its own filter, and a box above a
            // queue that has just answered would report the queue as broken. Without the number
            // there is no sentence, and the contradiction it accounts for is still on the screen,
            // so the failure is said in the same quiet voice the sentence would have been.
            setHiddenTotal(0);
            setHiddenError(describeApiError(e, 'alert-hidden'));
        }
    }

    async function handleSelect(id: number) {
        setSelectedId(id);
        selectedRef.current = id;
        setDetail(null);
        setDecisionMessage(null);
        setDecisionError(null);
        setAssignError(null);
        setHistory([]);
        setHistoryPage(null);
        setHistoryError(null);

        /*
         * The two boxes at the foot of the screen go with the alert they were typed about.
         *
         * They were the only things on this desk that did not: opening another alert left both of
         * them standing, and the next decision sent them. That is not a stale value on a screen, it
         * is one customer's suspicion written into another customer's record and, on a decline,
         * into the decline reason of their payment. The workstation has cleared its own pair since
         * it was written.
         */
        setDecisionComment('');
        setDecisionNote('');

        await loadDetail(id);
        // After the alert has landed and not before it. The panel is what the press is answered
        // with, and until the answer is in it the page is not tall enough to be scrolled to it.
        revealChoice(detailPanelRef.current);
    }

    /**
     * Reads one alert in full, and puts it on screen only if it is still the one open.
     *
     * Lifted out of handleSelect so that the retry beside its error box asks the same question
     * again rather than re-running the whole selection, which would throw away anything typed
     * into the decision boxes since.
     */
    async function loadDetail(id: number) {
        try {
            setLoadingDetail(true);
            setDetailError(null);
            const d = await fetchAlertDetail(id);
            if (selectedRef.current !== id) return;
            setDetail(d);
            // Nothing is loaded into the box below. It used to be filled with the stored notes,
            // because the field it wrote was one string that the press replaced whole; the box now
            // holds one entry to append, and seeding it with what colleagues have already written
            // would invite the analyst to edit a record that cannot be edited and to file it twice.
            // The journal itself is on screen, in the panel above the box, read from `detail`.
            //
            // The payments table is NOT filled from here, although the alert carries ten rows of
            // it. Two writers for one table is two answers to one question, and they disagree the
            // moment a payment is created between the two reads: the rows drawn from the alert are
            // then replaced by a different set with a different first row. The paged route beside
            // the alert is the only writer, which is what the workstation has always done, and it
            // is also the only one that carries the count the foot of the table states.
            void loadHistoryCount(id);
        } catch (e) {
            if (selectedRef.current !== id) return;
            setDetailError(
                describeApiFailure(e, 'alert-details', { retry: () => void loadDetail(id) }),
            );
        } finally {
            if (selectedRef.current === id) setLoadingDetail(false);
        }
    }

    /**
     * The customer's payments, and the only thing that ever fills that table.
     *
     * The alert sends ten rows of the same history and they are deliberately not read: see
     * loadDetail above for why one table with two writers is one table with two answers. It is
     * the cheap read of the two anyway, and it is the only one that carries the count: the
     * expensive one is the alert, the payment, the account and the customer behind it, and this
     * route touches none of them.
     *
     * Its failure used to be swallowed, on the argument that the ten rows from the alert are
     * still there. They no longer are, and even when they were that was the trap: the table then
     * stood at exactly ten rows with no count under it and no way to ask for an eleventh, which
     * is what a customer with ten payments looks like, so the panel quietly stated something
     * untrue about the one fact this route exists to supply.
     */
    async function loadHistoryCount(id: number) {
        try {
            setLoadingHistoryFirst(true);
            setHistoryError(null);
            const page = await fetchAlertHistory(id, 0, HISTORY_PAGE_SIZE);
            // The analyst may have moved to another alert while this was in flight, and this
            // answer names no alert: landed unchecked it would put one customer's payments under
            // another customer's alert.
            if (selectedRef.current !== id) return;
            setHistory(page.items);
            setHistoryPage(page);
        } catch (e) {
            if (selectedRef.current !== id) return;
            // Nothing is left in the table, since this route is what fills it, and the box says
            // so rather than letting an empty panel read as a customer with no payments. The
            // retry asks the same question again.
            setHistoryError(
                describeApiFailure(e, 'alert-history', {
                    retry: () => void loadHistoryCount(id),
                }),
            );
        } finally {
            if (selectedRef.current === id) setLoadingHistoryFirst(false);
        }
    }

    /**
     * The next page of the customer's payments, under the rows already there.
     *
     * Asked of the paged route and not of the alert, for the reason that route exists: turning a
     * page here must not make the server rebuild the alert to answer a question about the
     * customer's payments.
     */
    async function loadMoreHistory() {
        if (!historyPage || selectedId == null) return;

        try {
            setLoadingHistoryMore(true);
            setHistoryError(null);
            const page = await fetchAlertHistory(
                selectedId,
                nextPage(historyPage),
                HISTORY_PAGE_SIZE,
            );
            setHistory((held) => appendPage(held, page.items));
            setHistoryPage(page);
        } catch (e) {
            setHistoryError(
                describeApiFailure(e, 'alert-history', { retry: () => void loadMoreHistory() }),
            );
        } finally {
            setLoadingHistoryMore(false);
        }
    }

    /**
     * Takes the alert into the signed-in analyst's name, or gives it back to the queue.
     *
     * One function for the two, because they differ in one call and in nothing else. Neither
     * carries a body: the only name that can be written is the session's, which is what makes a
     * route open to every analyst safe to leave open, and it is why this desk has a control and a
     * filter here rather than the free text box that could never match anything.
     *
     * The queue is re-read behind it, keeping the rows that are open, because the assignee is a
     * column of the list as well as a fact of the panel.
     */
    async function changeAssignment(hold: boolean) {
        if (selectedId == null) return;

        try {
            setLoadingAssignment(true);
            setAssignError(null);
            const updated = hold ? await takeAlert(selectedId) : await releaseAlert(selectedId);
            // The two boxes are deliberately left alone: an assignment writes neither the comment
            // nor the journal, so anything typed into them is still waiting to be filed.
            setDetail(updated);
            await loadAlerts(true);
        } catch (e) {
            // No retry. Taking and releasing are writes, and a lost race here means the alert has
            // moved on: pressing again would ask the same question of a different alert.
            setAssignError(describeApiFailure(e, hold ? 'alert-assign' : 'alert-release'));
        } finally {
            setLoadingAssignment(false);
        }
    }

    async function handleDecision(kind: FraudDecision) {
        if (!selectedId || !detail) return;

        // The same rule as the dead button above, repeated where the request is built, so it
        // cannot be walked around one. The server refuses this too, and its refusal arrives as
        // the general validation sentence, which says nothing about a comment box: the words that
        // name what is missing are on this screen and nowhere else.
        //
        // Decline alone. The comment rides with all three presses, and only this one turns what
        // was typed into the sentence the customer is given.
        if (kind === 'DECLINE' && decisionComment.trim() === '') return;

        // Three fields, and the two that left are not coming back. `tags` went because nothing on
        // either desk could produce one and the two spellings of an empty list read as opposite
        // instructions on the server. `assignee` went because it has a route of its own: echoing
        // back the name that was read is enough to resurrect an assignment a colleague cleared in
        // the meantime. See FraudDecisionRequest, which carries the whole of it.
        //
        // A THIRD FIELD LEFT AND ONE ARRIVED IN ITS PLACE, and they are not the same field
        // renamed. `notes` carried the whole of an alert's notes and the press wrote what it was
        // given over what was there, which is why this desk had to work out whether the box had
        // been touched before it dared send it. `note` carries ONE entry to append, so a box left
        // alone is nothing to add and says so by being absent.
        //
        // `comment` is what the analyst concluded about THIS verdict, and it goes with all three
        // presses rather than belonging to the refusal. It was called `reason`, which is how it
        // came to be stored on the alert's own reason and printed as one line with it.
        const payload: FraudDecisionRequest = {
            decision: kind,
            comment: decisionComment.trim() || undefined,
            note: decisionNote.trim() || undefined,
        };

        try {
            setLoadingDecision(true);
            setPressed(kind);
            setDecisionError(null);
            // The previous verdict's Result block is cleared before this press, not after it. It
            // used to be left standing, so a refused second press put the outcome of the first
            // one beside the error that says this one did not happen.
            setDecisionMessage(null);

            // The answer carries the appended entry, so the journal above is redrawn from it and
            // the alert is deliberately not read again: between the two requests a colleague can
            // append, and the panel would then show a journal that does not match the result it is
            // announcing.
            const updated = await postFraudDecision(selectedId, payload);
            setDetail(updated);
            // The note box is emptied and the comment box is not, which is the difference between
            // appending and replacing. The entry has been filed and is now in the table above, so
            // leaving it here would offer to file the same paragraph a second time; the comment is
            // stored whole on each press, so a second press with it still in the box stores the
            // same string again and changes nothing. An analyst who saves a note and then declines
            // keeps what they had already written about the verdict.
            setDecisionNote('');
            // The sentence is the shared one, and the status it is told is the payment's AFTER
            // the decision. The two desks announced the same outcome in two different sentences
            // until this moved out of both of them.
            setDecisionMessage(describeDecision(kind, updated.transfer.status));

            // Keeps the decided alert on screen. With the default NEW filter it leaves the
            // queue the moment it is decided, and clearing the selection would unmount the
            // panel that shows what the decision did.
            await loadAlerts(true);
        } catch (e) {
            // Re-read before reporting, so what is on screen matches the server. The shared
            // table's sentence for a refused decision promises exactly that, and it names no
            // position on the screen: the two desks put this panel in different places.
            await loadAlerts(true);
            try {
                setDetail(await fetchAlertDetail(selectedId));
            } catch {
                // The alert may no longer be readable; the message does not depend on it.
            }

            // No retry: a decision is a verdict against a customer, and the reason a refused one
            // was refused is usually that somebody else has already recorded the opposite.
            setDecisionError(describeApiFailure(e, 'alert-decision'));
        } finally {
            setLoadingDecision(false);
            setPressed(null);
        }
    }

    function updateFilter<K extends keyof AlertFilters>(
        key: K,
        value: string,
    ) {
        setFilters((prev) => ({
            ...prev,
            [key]: value || undefined,
        }));
    }

    /*
     * The sentence that reconciles the list with the counters, built once because it belongs to
     * two branches below: under an empty queue, where it is the whole answer, and under a short
     * one, where it accounts for the difference.
     *
     * Where the number could not be read, the failure takes the same line and the same quiet
     * voice: the two are alternatives, never both, because the second is why the first is absent.
     */
    const hiddenNote = hiddenError ? (
        <p className="helper-text queue-note gap-above-sm">{hiddenError}</p>
    ) : hiddenTotal > 0 ? (
        <p className="helper-text queue-note gap-above-sm">{hiddenAlertsNote(hiddenTotal)}</p>
    ) : null;

    /**
     * Whether Decline is dead for want of a reason.
     *
     * A refusal is the one verdict on this desk that reaches the customer as words: the comment is
     * written to the payment's decline reason, and that is the whole of what the payer is told
     * about why their money did not move. Pressed with the box empty, this desk used to send
     * nothing and the bank filled the gap in with "Declined by fraud analyst", so the customer was
     * answered a question the analyst had been asked and had left blank.
     *
     * Approve and the third press are unaffected. The comment goes with all three, and only this
     * one turns it into somebody's answer.
     */
    const declineNeedsComment = decisionComment.trim() === '';

    return (
        <div className="app-shell">
            <div className="card">
                <header className="card-header">
                    {brand}
                    {identity}
                </header>

                <div className="card-body layout">
                    <Nav role={role} current="fraud-desk" onNavigate={onNavigate} />

                    {/* Right: queue, alert details and decision controls */}
                    <main className="form-panel">
                        {/*
                          Sentence case, like every other heading in both applications and like
                          the three panel titles under it, which came out of the glossary in that
                          case for the reason stated there. It also matters more than it did: the
                          navigation column is not drawn for a role with one screen, so this is
                          now the only place the analyst's screen is named.
                        */}
                        <h2>Fraud desk</h2>

                        {/*
                          Choose, read, decide: one cycle, and on a wide window one cycle laid out
                          across two columns rather than down three screenfuls. See the block in
                          App.css for what the two wrappers do and at what width they do it.
                        */}
                        <div className="work-split">
                        <div className="work-list">

                        {/* Alerts queue */}
                        <section className="section">
                            <h2 className="section-title">{ALERTS_QUEUE_TITLE}</h2>

                            {/*
                              A caption under the heading, not a panel above the queue. The four
                              numbers were set in a bordered box with a fill, which made them the
                              largest and lightest object on the screen and left the queue itself
                              the fourth thing the eye reached; they carry neither now, and the
                              queue is the only thing in this section with a shape of its own.

                              The numbers themselves are unchanged and so is what they mean. They
                              count the WHOLE queue, not the list below, because the server counts
                              before applying any filter and before any page: New 7 over a list of
                              three reads as a contradiction until the line says which number is
                              which. Counting the visible list instead would be worse, since this
                              page opens filtered to New and two of the three would be permanently
                              zero. The three states are the only three, so their sum is the queue.

                              What is NOT here is how much of the filtered list is on screen. That
                              is a different number, it comes from the page rather than from the
                              counters, and it stands on its own line directly above the rows it
                              is about.

                              The states are named in the words the queue below uses, and no
                              longer as OK and Suspicious beside rows that say Cleared and
                              Confirmed fraud. The sentence itself is the shared one: the three
                              words were typed here in lower case beside rows that capitalise
                              them, and the line did not say what it counted, which is the whole
                              point of it. One sentence from the shared layer, on both desks:
                              what this skin still decides is where the line sits and what it is
                              set in, and here it is a caption under the heading.
                            */}
                            {counters && (
                                <p className="section-caption">
                                    {queueCountersSentence(counters)}
                                </p>
                            )}

                            {/*
                              The filters of a queue, on one wrapping row rather than four
                              stacked rows of their own. Stacked, they pushed the queue 220px
                              down the page and off the first fold, so the desk opened as a form
                              about a queue instead of as the queue. Each label stands above the
                              control it names, and each control is as wide as what goes into it.
                            */}
                            <div className="section-block filter-bar">
                                <div className="filter-field">
                                    <label className="field-label" htmlFor="filter-state">
                                        {FIELD_LABEL.state}
                                    </label>
                                    {/* The options say what the queue and the panel below say:
                                        the two verdicts are what an analyst wrote, not moods. */}
                                    <select
                                        id="filter-state"
                                        className="field-input field-input--state"
                                        value={filters.state ?? ''}
                                        onChange={(e) =>
                                            updateFilter(
                                                'state',
                                                e.target.value,
                                            )
                                        }
                                    >
                                        <option value="">All</option>
                                        <option value="NEW">{alertStateLabel('NEW')}</option>
                                        <option value="SUSPICIOUS">
                                            {alertStateLabel('SUSPICIOUS')}
                                        </option>
                                        <option value="OK">{alertStateLabel('OK')}</option>
                                    </select>
                                </div>

                                {/*
                                  Text, and read by the same parser as the payment amount, so
                                  the string the queue prints one row above is a string this box
                                  accepts. It was type="number", which reports anything it cannot
                                  interpret as the empty string, and `10 001,00` is exactly that.
                                  The Czech spelling is written back on blur, so the reading the
                                  parser took is the one on screen.
                                */}
                                {/*
                                  Each box carries what is wrong with it, under itself. Both of
                                  them carry the mark when the two numbers are the wrong way round,
                                  because that refusal is about the pair and neither box is wrong
                                  on its own; the sentence saying so is written once, under the
                                  second of them, and both boxes point at it.
                                */}
                                <div className="filter-field">
                                    <label className="field-label" htmlFor="filter-amount-min">
                                        {AMOUNT_FROM_LABEL}
                                    </label>
                                    <input
                                        id="filter-amount-min"
                                        className="field-input field-input--amount"
                                        type="text"
                                        inputMode="decimal"
                                        placeholder={AMOUNT_PLACEHOLDER}
                                        value={amountText.min}
                                        aria-invalid={
                                            amountReason.min != null || rangeProblem != null
                                                ? true
                                                : undefined
                                        }
                                        aria-describedby={describeAmountBox('min')}
                                        onChange={(e) =>
                                            changeAmountBound('min', e.currentTarget.value)
                                        }
                                        onBlur={() => normalizeAmountBound('min')}
                                    />
                                    {amountReason.min && (
                                        <p className="field-error" id={AMOUNT_MIN_ERROR_ID}>
                                            {amountReason.min}
                                        </p>
                                    )}
                                </div>

                                <div className="filter-field">
                                    <label className="field-label" htmlFor="filter-amount-max">
                                        {AMOUNT_TO_LABEL}
                                    </label>
                                    <input
                                        id="filter-amount-max"
                                        className="field-input field-input--amount"
                                        type="text"
                                        inputMode="decimal"
                                        placeholder={AMOUNT_PLACEHOLDER}
                                        value={amountText.max}
                                        aria-invalid={
                                            amountReason.max != null || rangeProblem != null
                                                ? true
                                                : undefined
                                        }
                                        aria-describedby={describeAmountBox('max')}
                                        onChange={(e) =>
                                            changeAmountBound('max', e.currentTarget.value)
                                        }
                                        onBlur={() => normalizeAmountBound('max')}
                                    />
                                    {amountReason.max && (
                                        <p className="field-error" id={AMOUNT_MAX_ERROR_ID}>
                                            {amountReason.max}
                                        </p>
                                    )}
                                    {rangeProblem && (
                                        <p className="field-error" id={AMOUNT_RANGE_ERROR_ID}>
                                            {rangeProblem}
                                        </p>
                                    )}
                                </div>

                                {/*
                                  Two positions and not a box to type in.

                                  It was a free text field against a column nothing could write,
                                  so it matched nothing an analyst could ever put in it. There is
                                  no directory of analysts in this application and no way to hand
                                  an alert to a named colleague, so the only two questions the
                                  queue can answer about assignment are "mine" and "all of it",
                                  and Mine is this session's own login rather than a word typed
                                  into a box.
                                */}
                                <div className="filter-field">
                                    <label className="field-label" htmlFor="filter-assignee">
                                        {FIELD_LABEL.assignee}
                                    </label>
                                    <select
                                        id="filter-assignee"
                                        className="field-input field-input--login"
                                        value={filters.assignee ? 'mine' : 'all'}
                                        onChange={(e) =>
                                            updateFilter(
                                                'assignee',
                                                e.target.value === 'mine' ? username : '',
                                            )
                                        }
                                    >
                                        <option value="all">{ASSIGNED_TO_ANYONE}</option>
                                        <option value="mine">{ASSIGNED_TO_ME}</option>
                                    </select>
                                </div>

                                {/* A checkbox carries its label to its right, so it has none
                                    above it and stands on the line of the fields, not of their
                                    labels. */}
                                <label className="filter-check">
                                    <input
                                        type="checkbox"
                                        checked={
                                            !filters.excludeTransferStatus?.includes(
                                                WITHDRAWN,
                                            )
                                        }
                                        /*
                                          Read before the updater runs, not inside it. React
                                          clears currentTarget once the handler returns, and
                                          an updater passed to setState runs later, on the
                                          render pass: reading the event in there dereferenced
                                          null and took the whole screen down with it.
                                        */
                                        onChange={(e) => {
                                            const showWithdrawn = e.currentTarget.checked;
                                            setFilters((prev) => ({
                                                ...prev,
                                                excludeTransferStatus: showWithdrawn
                                                    ? undefined
                                                    : [WITHDRAWN],
                                            }));
                                        }}
                                    />
                                    {SHOW_WITHDRAWN_ALERTS}
                                </label>
                            </div>

                            {/* Work arrives from payments this desk does not make, and nothing on
                                this screen polls. Refreshing decides nothing, so it carries no
                                shape of its own, the same rank as Show more below. */}
                            <div className="section-block inline gap-above-sm">
                                <button
                                    type="button"
                                    className="btn-quiet"
                                    onClick={() => void handleRefresh()}
                                    disabled={refreshing}
                                    aria-busy={refreshing || undefined}
                                >
                                    {refreshing ? REFRESH_BUSY : REFRESH}
                                </button>
                            </div>

                            {/*
                              One statement where the queue goes, and only one. The error box used
                              to stand above this chain and outside it, so a refused filter was
                              printed over a table of live rows with "Showing: 1" under it, and an
                              empty queue said "No fraud alerts." while the counters beside it
                              said two. The counters stay put in every branch: they answer a
                              different question, and the sentence below now says which.

                              Rows outrank everything, so a reload in flight does not blank a
                              queue the analyst is reading; then whether a request is on its way;
                              then, on an empty screen, why it is empty.
                            */}
                            {alerts.length === 0 && loadingList ? (
                                <p className="helper-text">{QUEUE_LOADING}</p>
                            ) : alerts.length === 0 && listError ? (
                                <ErrorBox failure={listError} />
                            ) : alerts.length === 0 ? (
                                <>
                                    <p className="helper-text">{emptyQueueNote(filters)}</p>
                                    {hiddenNote}
                                </>
                            ) : (
                                <>
                                {/*
                                  Why the list can be shorter than the counters say. Drawn only
                                  when something is actually being hidden, which is also the only
                                  time the question comes up: the collision it explains is a naming
                                  collision on the wire, not a miscount, and until this sentence
                                  there was nothing on the screen that could say so.
                                */}
                                {hiddenNote}

                                {/*
                                  How much of the filtered list is on screen, directly above the
                                  rows it counts. It is not in the caption above: that one counts
                                  the whole queue before any filter, and standing the two side by
                                  side made a reader compare numbers that answer different
                                  questions.
                                */}
                                {/* The zone rides the same line, because both are facts about the
                                    table below and neither is worth a row of its own above a queue
                                    where rows are what the analyst came for. Same rung, one
                                    separator, so it reads as one line of metadata rather than as
                                    two statements competing. */}
                                <p className="list-count gap-above-sm">
                                    {showingLine(alerts.length, lastPage?.total ?? 0)}
                                    <span className="meta-sep">{TIMES_ZONE_NOTE}</span>
                                </p>

                                {/*
                                  Headers and cells both spread from the shared field set, in its
                                  reading order. The nine columns were written out twice by hand,
                                  which is how this desk came to head a column `Transfer` where
                                  the workstation says `Payment`, and `State` where it says
                                  `Alert state`. Neither desk can now leave a field out: the row
                                  is a total mapped type and a missing key does not build.
                                */}
                                <TableFrame
                                    label={ALERTS_QUEUE_TITLE}
                                    className="gap-above-sm"
                                >
                                    {/*
                                      A table at every width, and it stays one. Folded into a
                                      record per alert it printed the name of all nine columns
                                      against every row, so nine headings became nine times as many
                                      words and the queue stopped being something an eye can run
                                      down. A queue is read by comparing one column across rows,
                                      which is the one thing a stack of records cannot be read for;
                                      too narrow for nine columns, the frame around it scrolls.
                                    */}
                                    <table className="table">
                                        <thead>
                                        <tr>
                                            {/* Each heading claims its column. Nine columns read
                                                out cell by cell are nine values with no names on
                                                them unless the headings say which is which. */}
                                            {ALERT_QUEUE_FIELDS.map((f) => (
                                                <th
                                                    key={f}
                                                    scope="col"
                                                    className={QUEUE_CELL_CLASS[f]}
                                                >
                                                    {FIELD_LABEL[f]}
                                                </th>
                                            ))}
                                        </tr>
                                        </thead>
                                        <tbody>
                                        {alerts.map((a) => {
                                            const cells = queueCells(a);
                                            const open = selectedId === a.id;
                                            return (
                                                /*
                                                  A control, and not a row that happens to answer
                                                  a click. It was a bare <tr onClick>, with no tab
                                                  stop, no role and no key handler, so the only
                                                  way into this queue was a mouse: an analyst
                                                  working from the keyboard could reach the
                                                  filters, the Refresh button and Show more, and
                                                  nothing between them. The workstation draws each
                                                  entry as a <button> and has never had the
                                                  problem.

                                                  A table row cannot BE a button without losing
                                                  the row, so it is given what a button has: a tab
                                                  stop, the role, the pressed state the tint
                                                  already shows in colour, and the two keys a
                                                  reader will try. Space is caught on keydown as
                                                  well, because a page scrolls on the default and
                                                  the queue would jump under the selection.
                                                */
                                                <tr
                                                    key={a.id}
                                                    tabIndex={0}
                                                    role="button"
                                                    aria-pressed={open}
                                                    onClick={() => handleSelect(a.id)}
                                                    onKeyDown={(e) => {
                                                        if (
                                                            e.key !== 'Enter' &&
                                                            e.key !== ' '
                                                        ) {
                                                            return;
                                                        }
                                                        e.preventDefault();
                                                        void handleSelect(a.id);
                                                    }}
                                                    className={
                                                        open ? 'table-row--selected' : ''
                                                    }
                                                >
                                                    {/* Every cell carries the name of its own
                                                        column. At the width where the nine
                                                        columns give way, that name is what stands
                                                        beside the value in the record the row
                                                        becomes, so the headings do the same job
                                                        in both shapes and are written once. */}
                                                    {ALERT_QUEUE_FIELDS.map((f) => (
                                                        <td
                                                            key={f}
                                                            data-label={FIELD_LABEL[f]}
                                                            className={QUEUE_CELL_CLASS[f]}
                                                        >
                                                            {cells[f]}
                                                        </td>
                                                    ))}
                                                </tr>
                                            );
                                        })}
                                        </tbody>
                                    </table>
                                </TableFrame>

                                {/*
                                  The way to see more of the list, at the end of the rows where the
                                  reader is when they run out. The count that goes with it stands in
                                  the counters strip above the queue instead, so that the five
                                  numbers a reader compares are read in one place rather than at two
                                  ends of a table.
                                */}
                                <div className="section-block inline gap-above-sm">
                                    {hasMore(alerts.length, lastPage?.total ?? 0) && (
                                        <button
                                            type="button"
                                            className="btn-quiet"
                                            onClick={() => void loadMoreAlerts()}
                                            disabled={loadingMore}
                                            aria-busy={loadingMore || undefined}
                                        >
                                            {loadingMore ? SHOW_MORE_BUSY : SHOW_MORE}
                                        </button>
                                    )}
                                </div>

                                {/* The page that did not arrive, under the rows that did. It is
                                    about the request rather than about the queue, which is why it
                                    stands here and not where the table is. */}
                                {listError && <ErrorBox failure={listError} />}
                                </>
                            )}
                        </section>

                        </div>
                        <div className="work-detail">

                        {/* Details of the selected alert */}
                        <section className="section" ref={detailPanelRef}>
                            <h2 className="section-title">{ALERT_DETAILS_TITLE}</h2>

                            {/* The same rule as the queue: what is on its way, then why nothing
                                came, then the alert, then the sentence for a screen with nothing
                                open. The error box used to stand above all three. */}
                            {detailError && <ErrorBox failure={detailError} />}

                            {!detail && !loadingDetail && !detailError && (
                                <p className="helper-text">{SELECT_ALERT}</p>
                            )}

                            {loadingDetail && (
                                <p className="helper-text">{ALERT_DETAIL_LOADING}</p>
                            )}

                            {detail && !loadingDetail && (
                                <div className="section-block">
                                    {/*
                                      The labels were <strong>, which is 700, under a section
                                      heading at 600: every word naming a fact outweighed the
                                      fact it named. They step down in colour instead, and the
                                      two figures a decision turns on - the amount and the risk
                                      score - take the lead rung.

                                      The alert says the code the queue row above says. It read
                                      "Alert: 2 (NEW)" eight lines under a row saying ALERT-2.
                                    */}
                                    <div className="details-card">
                                        <h3 className="details-title">{ALERT_CARD_TITLE}</h3>
                                        <p>
                                            <span className="fact-label">Alert:</span>{' '}
                                            <span className="fact-value">
                                                {formatAlertId(detail.alert.id)} (
                                                <span
                                                    className={`tone-${alertStateTone(
                                                        detail.alert.state,
                                                    )}`}
                                                >
                                                    {alertStateLabel(detail.alert.state)}
                                                </span>
                                                )
                                            </span>
                                        </p>
                                        {/*
                                          Why the bank was worried, and now only that.

                                          It was captioned `Reason`, and it was carrying two
                                          claims: the server appended the analyst's own words to
                                          this column behind a bar, so one line held the rules'
                                          sentence about the payment and a person's conclusion
                                          about it with nothing between them, and a reader had no
                                          way to tell where the first ended. The comment has a
                                          field and a line of its own below. This one keeps the
                                          longer caption because it now stands near that line: the
                                          shorter of two words for two blocks of prose reads as
                                          the general case of the other.
                                        */}
                                        <p>
                                            <span className="fact-label">
                                                {ALERT_REASON_LABEL}:
                                            </span>{' '}
                                            <span className="fact-value">
                                                {detail.alert.reason}
                                            </span>
                                        </p>
                                        <p>
                                            <span className="fact-label">Risk score:</span>{' '}
                                            <span className="fact-value fact-value--lead">
                                                {detail.alert.riskScore ?? EMPTY_VALUE}
                                            </span>
                                        </p>
                                        <p>
                                            <span className="fact-label">
                                                {FIELD_LABEL.assignee}:
                                            </span>{' '}
                                            <span className="fact-value">
                                                {detail.alert.assignee || UNASSIGNED}
                                            </span>
                                        </p>
                                        <p>
                                            <span className="fact-label">Alert raised:</span>{' '}
                                            <span className="fact-value">
                                                {formatDateTime(detail.alert.createdAt)}
                                            </span>
                                        </p>

                                        {/*
                                          The verdict as recorded, who recorded it and when.

                                          All three were on the wire and none was drawn, so an
                                          alert this desk had already decided looked, once its
                                          state was read past, exactly like one nobody had
                                          touched.

                                          One test for the three, because they are written by one
                                          press and are null together. They used to have a test
                                          each, which is a different claim and a false one: it let
                                          the panel print a verdict and drop the hand that took
                                          it. On an alert nobody has decided none of the three is
                                          drawn at all, since three dashes would say a decision is
                                          known and withheld and the state one line above already
                                          says New.
                                        */}
                                        {detail.alert.decision && (
                                            <>
                                                <p>
                                                    <span className="fact-label">Decision:</span>{' '}
                                                    <span className="fact-value">
                                                        {decisionLabel(detail.alert.decision)}
                                                    </span>
                                                </p>
                                                {/*
                                                  Drawn on a decided alert whether or not a login
                                                  is behind it, which is why it is inside the test
                                                  above rather than under one of its own. A
                                                  decision taken at the console records no user,
                                                  and omitting the line made that alert read as if
                                                  nobody had decided it at all: the panel showed a
                                                  verdict and a time with no hand between them.
                                                  The words are the shared absence for a fact in
                                                  prose, not the table's dash.
                                                */}
                                                <p>
                                                    <span className="fact-label">Decided by:</span>{' '}
                                                    <span className="fact-value">
                                                        {detail.alert.decidedBy || NOT_RECORDED}
                                                    </span>
                                                </p>
                                                <p>
                                                    <span className="fact-label">Resolved:</span>{' '}
                                                    <span className="fact-value">
                                                        {formatDateTime(detail.alert.resolvedAt)}
                                                    </span>
                                                </p>
                                            </>
                                        )}

                                        {/*
                                          What the analyst concluded, on its own line and under a
                                          caption that says whose sentence it is.

                                          Outside the decision test above and not inside it,
                                          because the comment is not the property of a verdict:
                                          all three presses store it, and the one that stores it
                                          most often records no decision at all. An alert saved
                                          without a verdict has a comment and no `Decision` line,
                                          which is exactly the case a nested test would drop.

                                          Drawn only when there is something to draw. An analyst
                                          who took a verdict without a word and an alert nobody
                                          has touched are two situations with one value here, and
                                          neither has a sentence worth a line.
                                        */}
                                        {detail.alert.decisionComment && (
                                            <p>
                                                <span className="fact-label">
                                                    {DECISION_COMMENT_LABEL}:
                                                </span>{' '}
                                                <span className="fact-value">
                                                    {detail.alert.decisionComment}
                                                </span>
                                            </p>
                                        )}

                                        {/*
                                          Taking an alert and giving it back, which is the whole
                                          of assignment on this desk: the route writes the
                                          session's own name and accepts no other, so there is
                                          nothing to choose and nobody to choose it for.

                                          Both are always drawn and the dead one is disabled, never
                                          removed. A panel an analyst reads forty times a day must
                                          not move its controls under the pointer: adding and
                                          removing them meant the button under the cursor changed
                                          identity the moment an alert was taken, and the row was
                                          a different width on every alert. The workstation has
                                          drawn both since it was written.

                                          Take is refused only on an alert already in this
                                          analyst's name. Taking one a colleague holds is allowed
                                          at the server, deliberately, and so is releasing any of
                                          them: an alert held by somebody who has gone home must
                                          not be able to hold up the queue.
                                        */}
                                        <div className="actions gap-above-sm">
                                            <button
                                                type="button"
                                                className="btn-quiet"
                                                disabled={
                                                    loadingAssignment ||
                                                    detail.alert.assignee === username
                                                }
                                                aria-busy={loadingAssignment || undefined}
                                                onClick={() => void changeAssignment(true)}
                                            >
                                                {TAKE_ALERT}
                                            </button>
                                            <button
                                                type="button"
                                                className="btn-quiet"
                                                disabled={
                                                    loadingAssignment || !detail.alert.assignee
                                                }
                                                aria-busy={loadingAssignment || undefined}
                                                onClick={() => void changeAssignment(false)}
                                            >
                                                {RELEASE_ALERT}
                                            </button>
                                        </div>

                                        {assignError && <ErrorBox failure={assignError} />}
                                    </div>

                                    <div className="details-card gap-above-lg">
                                        <h3 className="details-title">{PAYMENT_CARD_TITLE}</h3>
                                        <p>
                                            <span className="fact-label">Transfer:</span>{' '}
                                            <span className="fact-value">
                                                {detail.transfer.code}
                                            </span>
                                        </p>
                                        <p>
                                            <span className="fact-label">Status:</span>{' '}
                                            <span
                                                className={`fact-value tone-${transferStatusTone(
                                                    detail.transfer.status,
                                                )}`}
                                            >
                                                {transferStatusLabel(
                                                    detail.transfer.status,
                                                    'analyst',
                                                )}
                                            </span>
                                        </p>
                                        <p>
                                            <span className="fact-label">From:</span>{' '}
                                            <span className="fact-value">
                                                {formatIban(detail.transfer.fromIban)} (Balance:{' '}
                                                {formatMoney(detail.transfer.fromBalance)})
                                            </span>
                                        </p>
                                        {/* A panel that lists facts one to a line is not scanned
                                            the way a column is, so here both readings are worth
                                            printing and the parenthesis is the one the From line
                                            above already uses for its balance. */}
                                        <p>
                                            <span className="fact-label">To:</span>{' '}
                                            <span className="fact-value">
                                                {formatIban(detail.transfer.toIban)} (
                                                {bankBoundaryLabel(
                                                    detail.transfer.toIbanInBank,
                                                )}
                                                )
                                            </span>
                                        </p>
                                        <p>
                                            <span className="fact-label">Amount:</span>{' '}
                                            <span className="fact-value fact-value--lead">
                                                {formatMoney(detail.transfer.amount)}
                                            </span>
                                        </p>
                                        <p>
                                            <span className="fact-label">Fee:</span>{' '}
                                            <span className="fact-value">
                                                {formatMoney(detail.transfer.feeAmount)}
                                            </span>
                                        </p>
                                        {/* Two timestamps on one panel, and neither label used
                                            to say which object it belonged to. */}
                                        <p>
                                            <span className="fact-label">Payment created:</span>{' '}
                                            <span className="fact-value">
                                                {formatDateTime(detail.transfer.createdAt)}
                                            </span>
                                        </p>
                                        {/*
                                          The end this payment came to, under the moment it was
                                          asked for. A payment settles or it is stopped, never
                                          both, so one line carries whichever happened and the
                                          label names which - the panel has room for a word per
                                          line, unlike the table, where the two share a cell under
                                          one heading.

                                          Both instants reached this panel's record and neither
                                          was ever drawn on it. So the desk could say a payment
                                          had been declined and not when, which is the same hole
                                          the customer's own history had, on the screen where the
                                          question is asked most often.

                                          Nothing is drawn while a payment is still going on,
                                          which is most of what an open queue holds.
                                        */}
                                        {(detail.transfer.settledAt ||
                                            detail.transfer.declinedAt) && (
                                            <p>
                                                <span className="fact-label">
                                                    {detail.transfer.settledAt
                                                        ? TRANSFER_DETAIL_LABEL.settledAt
                                                        : TRANSFER_DETAIL_LABEL.declinedAt}
                                                    :
                                                </span>{' '}
                                                <span className="fact-value">
                                                    {formatDateTime(
                                                        detail.transfer.settledAt ??
                                                            detail.transfer.declinedAt,
                                                    )}
                                                </span>
                                            </p>
                                        )}
                                        {/*
                                          What is still to happen to money that has left the
                                          account, on the line the shared field order gives it:
                                          after what the payment cost, before how it was
                                          authorized.

                                          Drawn only where the label has something to say, and
                                          the empty string is the whole reason it is a function
                                          and not a lookup. Null there is three different
                                          situations - credited inside this bank, not settled
                                          yet, written before the column existed - so a line
                                          reading "Onward transfer: none" would state as a fact
                                          about the money something this field cannot know. Which
                                          side of the bank it was going is on the To line above,
                                          and nowhere else.
                                        */}
                                        {dispatchStateLabel(detail.transfer.dispatchState) && (
                                            <p>
                                                <span className="fact-label">
                                                    {TRANSFER_DETAIL_LABEL.dispatchState}:
                                                </span>{' '}
                                                <span className="fact-value">
                                                    {dispatchStateLabel(
                                                        detail.transfer.dispatchState,
                                                    )}
                                                </span>
                                            </p>
                                        )}
                                        <p>
                                            <span className="fact-label">Auth method:</span>{' '}
                                            <span className="fact-value">
                                                {authMethodText(detail.transfer.authMethod)}
                                            </span>
                                        </p>
                                        {/*
                                          What the payer said they were paying for, on the line
                                          the shared field order gives it: last of the payment's
                                          facts, after how it was authorized.

                                          The one thing on this panel the customer wrote
                                          themselves, and the analyst could not read it. The form
                                          counts it against its limit and the bank stores it, and
                                          the one person reviewing that very payment saw every
                                          fact about it except the payer's own account of it.

                                          Printed only when there is text in it, which is the
                                          same restraint the customer's own history takes to the
                                          same field: a payment sent with the box left alone has
                                          nothing to show, and a line reading `Message for
                                          recipient: -` would say one was written and withheld.
                                          Under the caption the payer typed it under, so the two
                                          do not read as two fields.
                                        */}
                                        {detail.transfer.message && (
                                            <p>
                                                <span className="fact-label">
                                                    {TRANSFER_DETAIL_LABEL.message}:
                                                </span>{' '}
                                                <span className="fact-value">
                                                    {detail.transfer.message}
                                                </span>
                                            </p>
                                        )}
                                        {/*
                                          WHY THE BANK STOPPED IT, under the payer's own words,
                                          because that is the order the two sentences happened in
                                          and the bank's is the last word on a payment.

                                          This platform was the one that did not have it. The
                                          workstation gained the line and this panel did not, so
                                          the same alert read differently depending on which of
                                          the two applications an analyst opened - and the fact
                                          missing here is the one that says how the case ended.
                                          The two desks carry one role and must carry it whole.

                                          Read through the shared sentence rather than printed
                                          raw, which is what the history rows below do with the
                                          same field, and it is the guard as well: the helper
                                          answers the empty string for a payment that has not been
                                          refused, so nothing is drawn where there is nothing to
                                          say.

                                          Captioned Decline reason and never as the reason for a
                                          decision. This is the bank's sentence about the payment;
                                          what a person concluded is the analyst's comment further
                                          down, and the two were one field once already.
                                        */}
                                        {describeDeclineReason(detail.transfer.declineReason) && (
                                            <p>
                                                <span className="fact-label">
                                                    {TRANSFER_DETAIL_LABEL.declineReason}:
                                                </span>{' '}
                                                <span className="fact-value">
                                                    {describeDeclineReason(
                                                        detail.transfer.declineReason,
                                                    )}
                                                </span>
                                            </p>
                                        )}
                                    </div>

                                    <div className="details-card gap-above-lg">
                                        {/* The heading names the scope, because the scope grew:
                                            the table used to hold one account's payments under a
                                            sentence that said so, and now holds the customer's.
                                            Without the wording, the marked account below is
                                            marked for a reason nothing on screen states. */}
                                        <h3 className="details-title">{CUSTOMER_HISTORY_TITLE}</h3>
                                        {/* "No history" is a statement about the customer and is
                                            made only once the route that answers it has answered.
                                            The rows arrive after the panel around them now, so an
                                            empty table before then means nothing has come back
                                            yet, and the wait is said below in its own words. */}
                                        {history.length === 0 ? (
                                            historyPage && (
                                                <p className="helper-text">{NO_HISTORY}</p>
                                            )
                                        ) : (
                                            <TableFrame
                                                label={CUSTOMER_HISTORY_TITLE}
                                                className="gap-above-sm"
                                            >
                                                {/* Five columns from the shared field set, and the
                                                    two fields that open none: the decline reason
                                                    under the row it explains, the fee on the second
                                                    line of the amount it was added to. The first
                                                    column was headed ID here and Payment on the
                                                    workstation, for the same column holding the
                                                    same TR-9; one word now, decided once.

                                                    table--static: these rows open nothing. The
                                                    application's hover fill paints every table it
                                                    has, so without it a dead row lights up under
                                                    the pointer and reads as a control. */}
                                                <table className="table table--static">
                                                    <thead>
                                                    <tr>
                                                        {HISTORY_COLUMN_FIELDS.map((f) => (
                                                            <th
                                                                key={f}
                                                                scope="col"
                                                                className={HISTORY_CELL_CLASS[f]}
                                                            >
                                                                {HISTORY_FIELD_LABEL[f]}
                                                            </th>
                                                        ))}
                                                    </tr>
                                                    </thead>
                                                    {history.map((h) => {
                                                        const cells = historyCells(
                                                            h,
                                                            detail.transfer.fromIban,
                                                        );
                                                        return (
                                                            /* One row group per payment, so the
                                                               two pieces of prose it carries stay
                                                               part of the row they belong to. The
                                                               shared list decides which fields
                                                               those are and in what order: what
                                                               the payer wrote, then what the bank
                                                               wrote back. */
                                                            <tbody key={h.id}>
                                                            <tr>
                                                                {HISTORY_COLUMN_FIELDS.map((f) => (
                                                                    <td
                                                                        key={f}
                                                                        className={
                                                                            HISTORY_CELL_CLASS[f]
                                                                        }
                                                                    >
                                                                        {cells[f]}
                                                                    </td>
                                                                ))}
                                                            </tr>
                                                            {HISTORY_UNDER_ROW_FIELDS.filter(
                                                                (f) => cells[f],
                                                            ).map((f) => (
                                                                <tr
                                                                    className="history-note"
                                                                    key={f}
                                                                >
                                                                    <td
                                                                        colSpan={
                                                                            HISTORY_COLUMN_FIELDS
                                                                                .length
                                                                        }
                                                                    >
                                                                        {FIELD_LABEL[f]}:{' '}
                                                                        {cells[f]}
                                                                    </td>
                                                                </tr>
                                                            ))}
                                                            </tbody>
                                                        );
                                                    })}
                                                </table>
                                            </TableFrame>
                                        )}

                                        {/*
                                          How many payments this customer has, and the way to see
                                          the rest of them.

                                          The heading above admits that the table is cut and has
                                          never been able to say what it is cut out of: the ten
                                          rows travel inside the alert, which carries no count, so
                                          ten payments and two hundred looked identical here. The
                                          count and the control both come from the route beside
                                          the alert, and both are absent when that route has not
                                          answered.

                                          Which is why the wait and the failure are now said out
                                          loud. A panel that simply drew nothing here left the
                                          table standing at exactly ten rows with no count and no
                                          control, and ten rows with no count is what a customer
                                          with ten payments looks like: the silence was a claim,
                                          and on a failed read it was a false one.
                                        */}
                                        {loadingHistoryFirst && (
                                            <p className="helper-text gap-above-sm">
                                                {PAYMENTS_LOADING}
                                            </p>
                                        )}

                                        {historyPage && (
                                            <div className="section-block inline gap-above-sm">
                                                {hasMore(history.length, historyPage.total) && (
                                                    <button
                                                        type="button"
                                                        className="btn-quiet"
                                                        onClick={() => void loadMoreHistory()}
                                                        disabled={loadingHistoryMore}
                                                        aria-busy={loadingHistoryMore || undefined}
                                                    >
                                                        {loadingHistoryMore
                                                            ? SHOW_MORE_BUSY
                                                            : SHOW_MORE}
                                                    </button>
                                                )}
                                                <span className="list-count">
                                                    {showingLine(
                                                        history.length,
                                                        historyPage.total,
                                                    )}
                                                </span>
                                            </div>
                                        )}

                                        {/* The page of payments that did not arrive, beside the
                                            control that asked for it and under the rows that did. */}
                                        {historyError && <ErrorBox failure={historyError} />}
                                    </div>

                                    {/*
                                      The journal, last of the four panels and directly above the
                                      box that writes into it.

                                      It replaced a single string that every save overwrote, so
                                      two analysts on one alert erased each other and nothing
                                      recorded who had written what. It is append only: there is
                                      no control here to edit an entry and none to remove one,
                                      which is the whole of what the journal is for.

                                      A table and not a stack of paragraphs, because the two
                                      stamps beside each entry are what make it a record: an entry
                                      with no time and no name is the string this replaced. It
                                      arrives inside the alert and needs no request of its own,
                                      so unlike the payments beside it there is no wait to state
                                      and no failure of its own to report - if the alert is on the
                                      screen, so is the whole journal.
                                    */}
                                    <div className="details-card gap-above-lg">
                                        <h3 className="details-title">{ALERT_NOTES_TITLE}</h3>
                                        {detail.notes.length === 0 ? (
                                            /* Said plainly, and it invites nobody to write: the
                                               box that does that is in the panel below with a
                                               caption of its own, and a second invitation would
                                               be the screen asking twice. */
                                            <p className="helper-text">{NO_ALERT_NOTES}</p>
                                        ) : (
                                            <TableFrame
                                                label={ALERT_NOTES_TITLE}
                                                className="gap-above-sm"
                                            >
                                                {/* table--static: these rows open nothing, and
                                                    the application's hover fill paints every
                                                    table it has. */}
                                                <table className="table table--static">
                                                    <thead>
                                                    <tr>
                                                        {ALERT_NOTE_FIELDS.map((f) => (
                                                            <th
                                                                key={f}
                                                                scope="col"
                                                                className={NOTE_CELL_CLASS[f]}
                                                            >
                                                                {ALERT_NOTE_LABEL[f]}
                                                            </th>
                                                        ))}
                                                    </tr>
                                                    </thead>
                                                    <tbody>
                                                    {detail.notes.map((n, i) => {
                                                        const cells = noteCells(n);
                                                        return (
                                                            /* The position is the key, because an
                                                               entry carries no id and two written
                                                               in the same second would collide on
                                                               their timestamp. It is safe here and
                                                               would not be in the queue beside it:
                                                               this list only ever grows at its end,
                                                               oldest first, and nothing on the
                                                               screen sorts it or filters it, so a
                                                               row's position is fixed for as long
                                                               as it exists. */
                                                            <tr key={i}>
                                                                {ALERT_NOTE_FIELDS.map((f) => (
                                                                    <td
                                                                        key={f}
                                                                        className={
                                                                            NOTE_CELL_CLASS[f]
                                                                        }
                                                                    >
                                                                        {cells[f]}
                                                                    </td>
                                                                ))}
                                                            </tr>
                                                        );
                                                    })}
                                                    </tbody>
                                                </table>
                                            </TableFrame>
                                        )}
                                    </div>
                                </div>
                            )}
                        </section>

                        {/* Decision controls for the selected alert */}
                        <section className="section">
                            <h2 className="section-title">{DECISION_TITLE}</h2>

                            {!detail && (
                                <p className="helper-text">{SELECT_ALERT_TO_DECIDE}</p>
                            )}

                            {detail && (
                                <>
                                    <div className="section-block">
                                        <div className="field-column">
                                            {/* One caption for both desks, and it names no
                                                verdict: the comment rides with all three
                                                decisions, so "reason for declining" would tell
                                                an analyst clearing an alert that what they are
                                                writing is for a refusal they are not making. It
                                                also may not say `reason`, which is the word the
                                                alert's own line above is under. */}
                                            <label
                                                className="field-label"
                                                htmlFor="decision-comment"
                                            >
                                                {DECISION_COMMENT_LABEL}
                                                {/* The star, and the line under the block that
                                                    reads it. A rule set between two boxes puts
                                                    prose where the eye is looking for the next
                                                    field. */}
                                                <span aria-hidden="true"> *</span>
                                            </label>
                                            <textarea
                                                id="decision-comment"
                                                className="textarea"
                                                rows={3}
                                                maxLength={MAX_DECISION_COMMENT}
                                                value={decisionComment}
                                                onChange={(e) =>
                                                    setDecisionComment(
                                                        e.target.value,
                                                    )
                                                }
                                                placeholder={DECISION_COMMENT_PLACEHOLDER}
                                            />
                                            <div className="message-counter">
                                                {decisionComment.length}/{MAX_DECISION_COMMENT}
                                            </div>
                                        </div>

                                        {/* One entry to add, and it starts empty on every alert
                                            and empties again once it is filed. It was captioned
                                            `Internal notes` and held the whole of the alert's
                                            notes for editing, which is what let one analyst
                                            write over another's paragraph without seeing it. The
                                            caption is a verb now, because what the box does is
                                            what changed: the record itself is the table above,
                                            where it can be read and not touched. */}
                                        {/* No rung above it: the count under the box before it is
                                            already a line of its own, set small and quiet at the
                                            end of that box, and it is the seam a margin here was
                                            added to make. */}
                                        <div className="field-column">
                                            <label
                                                className="field-label"
                                                htmlFor="decision-note"
                                            >
                                                {DECISION_NOTE_LABEL}
                                            </label>
                                            <textarea
                                                id="decision-note"
                                                className="textarea"
                                                rows={3}
                                                maxLength={MAX_DECISION_NOTE}
                                                value={decisionNote}
                                                onChange={(e) =>
                                                    setDecisionNote(
                                                        e.target.value,
                                                    )
                                                }
                                                placeholder={DECISION_NOTE_PLACEHOLDER}
                                            />
                                            <div className="message-counter">
                                                {decisionNote.length}/{MAX_DECISION_NOTE}
                                            </div>
                                        </div>

                                        {/* The buttons mirror the domain guards exactly, so a
                                            click that the server would refuse - and whose
                                            refusal would take what was typed down with it -
                                            is not reachable. Approve only from NEW; Decline
                                            from anything but SUSPICIOUS, which is what lets
                                            fraud confirmed after the money left be recorded on
                                            an alert that was already cleared.

                                            Three consequences, three shapes. Declining records
                                            a verdict against a customer and cannot be taken
                                            back, so it is the destructive edge; the third press
                                            takes no verdict, so it is the neutral rectangle and
                                            the gap in front of it says it is not one of the
                                            pair. It carried no shape at all until now, which was
                                            a rank too far down: it POSTS to the same route as
                                            the two beside it and appends to the case notes, and a
                                            real write set as borderless text is indistinguishable
                                            from a caption. The level with no shape is for
                                            controls that change nothing on the server, which is
                                            Refresh and Show more and not this.

                                            All three go dead while any one of them is in
                                            flight, because the server takes one verdict per
                                            alert and the second press would be refused with what
                                            has been typed in it. Only the pressed one changes its
                                            word: three buttons reading Applying… would say three
                                            decisions were being taken. */}
                                        {/* Why Decline is dead while the box above it is empty,
                                            standing over the buttons rather than under them so it
                                            is read on the way to a press rather than after one.
                                            The star on the box's own label says which box. The
                                            workstation says the same sentence in the same place,
                                            for the same reason. */}
                                        <p className="helper-text">
                                            * {DECLINE_NEEDS_COMMENT}
                                        </p>

                                        <div className="actions gap-above-lg">
                                            <button
                                                type="button"
                                                className="btn-primary"
                                                disabled={
                                                    loadingDecision ||
                                                    detail.alert.state !== 'NEW'
                                                }
                                                aria-busy={
                                                    pressed === 'APPROVE' || undefined
                                                }
                                                onClick={() =>
                                                    handleDecision('APPROVE')
                                                }
                                            >
                                                {pressed === 'APPROVE'
                                                    ? DECISION_BUSY
                                                    : decisionActionLabel('APPROVE')}
                                            </button>
                                            <button
                                                type="button"
                                                className="btn-secondary btn-secondary--danger"
                                                disabled={
                                                    loadingDecision ||
                                                    detail.alert.state === 'SUSPICIOUS' ||
                                                    declineNeedsComment
                                                }
                                                aria-busy={
                                                    pressed === 'DECLINE' || undefined
                                                }
                                                onClick={() =>
                                                    handleDecision('DECLINE')
                                                }
                                            >
                                                {pressed === 'DECLINE'
                                                    ? DECISION_BUSY
                                                    : decisionActionLabel('DECLINE')}
                                            </button>
                                            {/* ANNOTATE. The token used to be called
                                                REQUEST_CONFIRMATION, which named something it
                                                has never done: it asks nobody for anything. */}
                                            <button
                                                type="button"
                                                className="btn-secondary push-end"
                                                disabled={loadingDecision}
                                                aria-busy={
                                                    pressed === 'ANNOTATE' || undefined
                                                }
                                                onClick={() =>
                                                    handleDecision('ANNOTATE')
                                                }
                                            >
                                                {pressed === 'ANNOTATE'
                                                    ? DECISION_BUSY
                                                    : decisionActionLabel('ANNOTATE')}
                                            </button>
                                        </div>

                                        {/* Only against a payment that has already gone, which
                                            is the one case the buttons cannot show themselves. */}
                                        {detail.transfer.status === 'SENT' && (
                                            <p className="helper-text">{DECLINE_ALREADY_SENT}</p>
                                        )}
                                    </div>

                                    {decisionError && <ErrorBox failure={decisionError} />}

                                    {decisionMessage && (
                                        /* An irreversible decision that reached the database, and
                                           the edge says so whichever of the three was pressed:
                                           what worked is the recording, not the verdict. A cleared
                                           alert and a confirmed fraud are both the desk's own
                                           answer written down, and neither is a failure to tell
                                           the analyst about.

                                           The word over it is the shared one. Both desks had
                                           reached it independently and both typed it, which is the
                                           cheapest kind of agreement to lose. */
                                        <div className="summary summary--success gap-above-sm">
                                            <div className="summary-title">
                                                {DECISION_RESULT_TITLE}
                                            </div>
                                            <ul>
                                                <li>{decisionMessage}</li>
                                            </ul>
                                        </div>
                                    )}
                                </>
                            )}
                        </section>

                        </div>
                        </div>
                    </main>
                </div>
            </div>
        </div>
    );
}
