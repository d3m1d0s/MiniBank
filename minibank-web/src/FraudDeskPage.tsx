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
import { describeApiFailure, type ApiFailure } from '@shared/apiErrors';
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
    EMPTY_VALUE,
    formatAlertId,
    formatDateTime,
    formatIban,
    formatTransferId,
    NOT_RECORDED,
} from '@shared/format';
import {
    ALERT_QUEUE_FIELDS,
    FIELD_LABEL,
    HISTORY_FIELD_LABEL,
    HISTORY_COLUMN_FIELDS,
    HISTORY_NOTE_FIELD,
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
import type { NavRole, NavView } from '@shared/navigation';

/** The transfer status a withdrawn payment ends in. */
const WITHDRAWN = 'DECLINED';

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
 * Why the queue can be empty while the counters above it are not.
 *
 * One transfer status carries two meanings that read as opposites on this desk: a payment the
 * customer withdrew and a payment an analyst declined are both DECLINED. Hiding the first is the
 * sensible default, since there is nothing left to decide on it, and it hides the second with it,
 * so the desk opens on Cleared 1 over a list that says no alerts. The rule is not changed here,
 * the server's exclusion is right; what was missing is the number that reconciles the two, and
 * that is what the hidden route answers.
 */
function hiddenAlertsNote(hidden: number): string {
    const subject = hidden === 1 ? '1 alert is' : `${hidden} alerts are`;
    return (
        `${subject} not listed, because the payment behind it was withdrawn by the customer. ` +
        'A withdrawn payment and one an analyst declined are both DECLINED, so this desk cannot ' +
        'hide the first without hiding the second, and the counters above go on counting both. ' +
        'Show alerts on cancelled payments brings them back.'
    );
}

/**
 * Why the list is empty, in the words that stop it contradicting the numbers above it.
 *
 * "No fraud alerts." stood under a caption reading "Whole queue: 2 alerts. New 1, ...", set in the
 * same quiet grey, and the two cannot both be answers to the same question. They are not: the
 * counters count the whole queue before any filter, and this list is what the filters matched. The
 * desk opens filtered - New, and withdrawn payments hidden - so the filtered wording is the usual
 * one rather than the exception, and it points at the row of controls that would widen it.
 */
function emptyQueueNote(filters: AlertFilters): string {
    const filtered =
        Boolean(filters.state) ||
        Boolean(filters.assignee) ||
        Boolean(filters.minAmount) ||
        Boolean(filters.maxAmount) ||
        Boolean(filters.createdFrom) ||
        Boolean(filters.createdTo) ||
        Boolean(filters.excludeTransferStatus?.length);

    return filtered
        ? 'No alerts match the filters above.'
        : 'There are no fraud alerts.';
}

/**
 * Which queue columns are more than left-aligned text.
 *
 * Marked where the cell is built rather than found by counting header cells in the stylesheet,
 * which matches a fixed number of columns and breaks silently the day one is added. The
 * workstation already does it this way.
 */
const QUEUE_CELL_CLASS: Partial<Record<AlertQueueField, string>> = {
    amount: 'cell--amount',
};

const HISTORY_CELL_CLASS: Partial<Record<HistoryField, string>> = {
    amount: 'cell--amount',
};

/**
 * A queue entry's nine fields, ready to be laid out.
 *
 * Built through the shared row type so a field the server sends and this desk forgets is a build
 * failure rather than something an analyst discovers is missing. The workstation reads a queue
 * entry down a card and this one reads it across a table, so each builds its own cells and only
 * the field set and the words are shared.
 *
 * Two cells differ from the workstation's on purpose. A column has a heading, so an absent risk
 * score or assignee is the data table's dash here, where the card, having no headings, has to say
 * `Risk 80` and `unassigned` in words. format.ts blesses exactly that split.
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
        shortReason: a.shortReason,
        riskScore: a.riskScore ?? EMPTY_VALUE,
        assignee: a.assignee || EMPTY_VALUE,
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
        // The same sentence the customer is now shown for their own declined payment, from the
        // same function. No absent value: a payment that was not declined has no note row at all.
        declineReason: describeDeclineReason(h.declineReason),
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
     * The customer's payments beside the alert, and the page envelope they arrived in.
     *
     * Held apart from `detail` because the two have different lifetimes. The detail carries ten
     * rows and no count, so a panel reading it alone can never tell a customer with ten payments
     * from one with two hundred; this comes from the route beside it, which counts them and pages
     * them, and turning a page here does not make the server rebuild the alert.
     */
    const [history, setHistory] = useState<HistoryItem[]>([]);
    const [historyPage, setHistoryPage] = useState<Page<HistoryItem> | null>(null);
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

    const [decisionReason, setDecisionReason] = useState('');
    const [decisionNotes, setDecisionNotes] = useState('');

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
            // again: the way out is the box the analyst typed into.
            setListError({ lines: [problem], reference: null });
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
     * The queue and nothing else. It deliberately does not re-read the open alert: that read
     * re-adopts the stored notes, and it would throw away the paragraph the analyst is in the
     * middle of typing, which is the same rule the assignment call already follows.
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
            return;
        }

        try {
            const hidden = await fetchHiddenAlerts(filters, 0, 1);
            setHiddenTotal(hidden.total);
        } catch {
            // A sentence that explains the list is not worth an error box above the list. With
            // no number there is nothing to say, so nothing is said.
            setHiddenTotal(0);
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
         * They were the only things on this desk that did not: opening another alert left the
         * reason and the notes standing, and the next decision sent them. That is not a stale
         * value on a screen, it is one customer's suspicion written into another customer's
         * record and, on a decline, into the decline reason of their payment. The workstation has
         * cleared its own pair since it was written.
         *
         * The notes are emptied here rather than left for the answer to overwrite, because the
         * answer may not come: a failed or slow read would otherwise leave a colleague's
         * paragraph about the previous alert in an editable box under this one.
         */
        setDecisionReason('');
        setDecisionNotes('');

        await loadDetail(id);
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
            // The notes box is a copy of the column and not a blank sheet. It opened empty, so
            // an analyst adding one line replaced a colleague's paragraph with it, unseen: the
            // decision route takes this field whole and writes what it is given.
            setDecisionNotes(d.alert.notes ?? '');
            setHistory(d.history);
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
     * The first page of the same history, asked for so the panel can say how many there are.
     *
     * These are the ten rows the detail already carries, so nothing on screen moves; what arrives
     * with them is the count, which the detail cannot send. It is the cheap read of the two: the
     * expensive one is the alert, the payment, the account and the customer behind it, and this
     * route touches none of them.
     */
    async function loadHistoryCount(id: number) {
        try {
            const page = await fetchAlertHistory(id, 0, HISTORY_PAGE_SIZE);
            // The analyst may have moved to another alert while this was in flight, and this
            // answer names no alert: landed unchecked it would put one customer's payments under
            // another customer's alert.
            if (selectedRef.current !== id) return;
            setHistory(page.items);
            setHistoryPage(page);
        } catch {
            // The ten rows from the detail stay where they are. Without the page there is no
            // total, so the count line and the control under it are simply not drawn.
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
            // The notes box is deliberately left alone: an assignment changes no notes, and
            // re-adopting the column here would throw away what the analyst has been typing.
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

        // Three fields, and the two that left are not coming back. `tags` went because nothing on
        // either desk could produce one and the two spellings of an empty list read as opposite
        // instructions on the server. `assignee` went because it has a route of its own: echoing
        // back the name that was read is enough to resurrect an assignment a colleague cleared in
        // the meantime. See FraudDecisionRequest, which carries the whole of it.
        const payload: FraudDecisionRequest = {
            decision: kind,
            reason: decisionReason.trim() || undefined,
            notes: decisionNotes.trim() || undefined,
        };

        try {
            setLoadingDecision(true);
            setDecisionError(null);

            const updated = await postFraudDecision(selectedId, payload);
            setDetail(updated);
            // Back to being a copy of the column: what was just sent is now what is stored, and
            // the next edit is again an addition to the record rather than a replacement of it.
            setDecisionNotes(updated.alert.notes ?? '');
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
     */
    const hiddenNote =
        hiddenTotal > 0 ? (
            <p className="helper-text gap-above-sm">{hiddenAlertsNote(hiddenTotal)}</p>
        ) : null;

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
                        <h2>Fraud Desk</h2>

                        {/* Alerts queue */}
                        <section className="section">
                            <h2 className="section-title">Alerts queue</h2>

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
                              Confirmed fraud.
                            */}
                            {counters && (
                                <p className="section-caption">
                                    Whole queue:{' '}
                                    {counters.newCount +
                                        counters.suspiciousCount +
                                        counters.okCount}{' '}
                                    alerts. New {counters.newCount}, confirmed fraud{' '}
                                    {counters.suspiciousCount}, cleared {counters.okCount}.
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
                                        State
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
                                <div className="filter-field">
                                    <label className="field-label" htmlFor="filter-amount-min">
                                        Amount from
                                    </label>
                                    <input
                                        id="filter-amount-min"
                                        className="field-input field-input--amount"
                                        type="text"
                                        inputMode="decimal"
                                        placeholder="0,00"
                                        value={amountText.min}
                                        onChange={(e) =>
                                            changeAmountBound('min', e.currentTarget.value)
                                        }
                                        onBlur={() => normalizeAmountBound('min')}
                                    />
                                </div>

                                <div className="filter-field">
                                    <label className="field-label" htmlFor="filter-amount-max">
                                        Amount to
                                    </label>
                                    <input
                                        id="filter-amount-max"
                                        className="field-input field-input--amount"
                                        type="text"
                                        inputMode="decimal"
                                        placeholder="0,00"
                                        value={amountText.max}
                                        onChange={(e) =>
                                            changeAmountBound('max', e.currentTarget.value)
                                        }
                                        onBlur={() => normalizeAmountBound('max')}
                                    />
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
                                    Show alerts on cancelled payments
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
                                    {refreshing ? 'Refreshing…' : 'Refresh'}
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
                                <p className="helper-text">
                                    Loading alerts…
                                </p>
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
                                <p className="list-count gap-above-sm">
                                    {showingLine(alerts.length, lastPage?.total ?? 0)}
                                </p>

                                {/*
                                  Headers and cells both spread from the shared field set, in its
                                  reading order. The nine columns were written out twice by hand,
                                  which is how this desk came to head a column `Transfer` where
                                  the workstation says `Payment`, and `State` where it says
                                  `Alert state`. Neither desk can now leave a field out: the row
                                  is a total mapped type and a missing key does not build.
                                */}
                                <div className="table-wrapper gap-above-sm">
                                    <table className="table">
                                        <thead>
                                        <tr>
                                            {ALERT_QUEUE_FIELDS.map((f) => (
                                                <th key={f} className={QUEUE_CELL_CLASS[f]}>
                                                    {FIELD_LABEL[f]}
                                                </th>
                                            ))}
                                        </tr>
                                        </thead>
                                        <tbody>
                                        {alerts.map((a) => {
                                            const cells = queueCells(a);
                                            return (
                                                <tr
                                                    key={a.id}
                                                    onClick={() =>
                                                        handleSelect(a.id)
                                                    }
                                                    className={
                                                        selectedId === a.id
                                                            ? 'table-row--selected'
                                                            : ''
                                                    }
                                                >
                                                    {ALERT_QUEUE_FIELDS.map((f) => (
                                                        <td
                                                            key={f}
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
                                </div>

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

                        {/* Details of the selected alert */}
                        <section className="section">
                            <h2 className="section-title">Alert details</h2>

                            {/* The same rule as the queue: what is on its way, then why nothing
                                came, then the alert, then the sentence for a screen with nothing
                                open. The error box used to stand above all three. */}
                            {detailError && <ErrorBox failure={detailError} />}

                            {!detail && !loadingDetail && !detailError && (
                                <p className="helper-text">
                                    Select an alert from the queue.
                                </p>
                            )}

                            {loadingDetail && (
                                <p className="helper-text">
                                    Loading alert details…
                                </p>
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
                                        <p>
                                            <span className="fact-label">Reason:</span>{' '}
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
                                          touched. Absent together on an alert nobody has
                                          decided: three dashes would say a decision is known and
                                          withheld, and the state one line above already says New.
                                        */}
                                        {detail.alert.decision && (
                                            <p>
                                                <span className="fact-label">Decision:</span>{' '}
                                                <span className="fact-value">
                                                    {decisionLabel(detail.alert.decision)}
                                                </span>
                                            </p>
                                        )}
                                        {detail.alert.decidedBy && (
                                            <p>
                                                <span className="fact-label">Decided by:</span>{' '}
                                                <span className="fact-value">
                                                    {detail.alert.decidedBy}
                                                </span>
                                            </p>
                                        )}
                                        {detail.alert.resolvedAt && (
                                            <p>
                                                <span className="fact-label">Resolved:</span>{' '}
                                                <span className="fact-value">
                                                    {formatDateTime(detail.alert.resolvedAt)}
                                                </span>
                                            </p>
                                        )}

                                        {/*
                                          What colleagues have written about this alert, where
                                          the facts about it are. It is also loaded into the box
                                          at the foot of the screen, which is the copy that gets
                                          edited; this is the copy of record, and it stays
                                          readable while that one is being typed into.
                                        */}
                                        {detail.alert.notes && (
                                            <p>
                                                <span className="fact-label">Notes:</span>{' '}
                                                <span className="fact-value">
                                                    {detail.alert.notes}
                                                </span>
                                            </p>
                                        )}

                                        {/*
                                          Taking an alert and giving it back, which is the whole
                                          of assignment on this desk: the route writes the
                                          session's own name and accepts no other, so there is
                                          nothing to choose and nobody to choose it for.

                                          Both controls appear on an alert somebody else holds.
                                          Any analyst may release any alert, deliberately, at the
                                          server: one held by an analyst who has gone home must
                                          not be able to hold up the queue.
                                        */}
                                        <div className="actions gap-above-sm">
                                            {detail.alert.assignee !== username && (
                                                <button
                                                    type="button"
                                                    className="btn-quiet"
                                                    disabled={loadingAssignment}
                                                    aria-busy={loadingAssignment || undefined}
                                                    onClick={() => void changeAssignment(true)}
                                                >
                                                    {TAKE_ALERT}
                                                </button>
                                            )}
                                            {detail.alert.assignee && (
                                                <button
                                                    type="button"
                                                    className="btn-quiet"
                                                    disabled={loadingAssignment}
                                                    aria-busy={loadingAssignment || undefined}
                                                    onClick={() => void changeAssignment(false)}
                                                >
                                                    {RELEASE_ALERT}
                                                </button>
                                            )}
                                        </div>

                                        {assignError && <ErrorBox failure={assignError} />}
                                    </div>

                                    <div className="details-card gap-above-lg">
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
                                        <p>
                                            <span className="fact-label">Auth method:</span>{' '}
                                            <span className="fact-value">
                                                {authMethodLabel(detail.transfer.authMethod) ||
                                                    NOT_RECORDED}
                                            </span>
                                        </p>
                                    </div>

                                    <div className="details-card gap-above-lg">
                                        {/* The heading names the scope, because the scope grew:
                                            the table used to hold one account's payments under a
                                            sentence that said so, and now holds the customer's.
                                            Without the wording, the marked account below is
                                            marked for a reason nothing on screen states. */}
                                        <p>
                                            <strong>{CUSTOMER_HISTORY_TITLE}</strong>
                                        </p>
                                        {history.length === 0 ? (
                                            <p className="helper-text">
                                                No history.
                                            </p>
                                        ) : (
                                            <div className="table-wrapper gap-above-sm">
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
                                                               reason a payment was refused stays
                                                               part of the row it explains. */
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
                                                            {h.declineReason && (
                                                                <tr className="history-note">
                                                                    <td
                                                                        colSpan={
                                                                            HISTORY_COLUMN_FIELDS
                                                                                .length
                                                                        }
                                                                    >
                                                                        {
                                                                            FIELD_LABEL[
                                                                                HISTORY_NOTE_FIELD
                                                                            ]
                                                                        }
                                                                        :{' '}
                                                                        {
                                                                            cells[
                                                                                HISTORY_NOTE_FIELD
                                                                            ]
                                                                        }
                                                                    </td>
                                                                </tr>
                                                            )}
                                                            </tbody>
                                                        );
                                                    })}
                                                </table>
                                            </div>
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
                                          answered, which leaves exactly the panel that was here
                                          before.
                                        */}
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
                                </div>
                            )}
                        </section>

                        {/* Decision controls for the selected alert */}
                        <section className="section">
                            <h2 className="section-title">Decision</h2>

                            {!detail && (
                                <p className="helper-text">
                                    Select an alert to take a decision.
                                </p>
                            )}

                            {detail && (
                                <>
                                    <div className="section-block">
                                        <div className="field-column">
                                            {/* One caption for both desks, and it names no
                                                verdict: the comment rides with all three
                                                decisions, so "reason for declining" would tell
                                                an analyst clearing an alert that what they are
                                                writing is for a refusal they are not making. */}
                                            <label className="field-label">
                                                {DECISION_REASON_LABEL}
                                            </label>
                                            <textarea
                                                className="textarea"
                                                rows={3}
                                                value={decisionReason}
                                                onChange={(e) =>
                                                    setDecisionReason(
                                                        e.target.value,
                                                    )
                                                }
                                                placeholder="Optional comment that will be stored with the alert."
                                            />
                                        </div>

                                        <div className="field-column gap-above-sm">
                                            <label className="field-label">
                                                Internal notes
                                            </label>
                                            <textarea
                                                className="textarea"
                                                rows={3}
                                                value={decisionNotes}
                                                onChange={(e) =>
                                                    setDecisionNotes(
                                                        e.target.value,
                                                    )
                                                }
                                                placeholder="Additional notes for future investigation."
                                            />
                                        </div>

                                        {/* The buttons mirror the domain guards exactly, so a
                                            click that the server would refuse - and whose
                                            refusal would take the typed notes down with it -
                                            is not reachable. Approve only from NEW; Decline
                                            from anything but SUSPICIOUS, which is what lets
                                            fraud confirmed after the money left be recorded on
                                            an alert that was already cleared.

                                            Three consequences, three shapes. Declining records
                                            a verdict against a customer and cannot be taken
                                            back, so it is the destructive edge; saving notes
                                            decides nothing, so it carries no shape and the gap
                                            in front of it says it is not one of the pair. The
                                            last two were the same two white rectangles. */}
                                        <div className="actions gap-above-lg">
                                            <button
                                                type="button"
                                                className="btn-primary"
                                                disabled={
                                                    loadingDecision ||
                                                    detail.alert.state !== 'NEW'
                                                }
                                                onClick={() =>
                                                    handleDecision('APPROVE')
                                                }
                                            >
                                                {loadingDecision
                                                    ? 'Applying…'
                                                    : decisionActionLabel('APPROVE')}
                                            </button>
                                            <button
                                                type="button"
                                                className="btn-secondary btn-secondary--danger"
                                                disabled={
                                                    loadingDecision ||
                                                    detail.alert.state === 'SUSPICIOUS'
                                                }
                                                onClick={() =>
                                                    handleDecision('DECLINE')
                                                }
                                            >
                                                {decisionActionLabel('DECLINE')}
                                            </button>
                                            {/* ANNOTATE. The token used to be called
                                                REQUEST_CONFIRMATION, which named something it
                                                has never done: it asks nobody for anything. */}
                                            <button
                                                type="button"
                                                className="btn-quiet push-end"
                                                disabled={loadingDecision}
                                                onClick={() =>
                                                    handleDecision('ANNOTATE')
                                                }
                                            >
                                                {decisionActionLabel('ANNOTATE')}
                                            </button>
                                        </div>

                                        <p className="helper-text">
                                            Approving does not send the money: it releases the
                                            payment for the customer to confirm. Declining a
                                            payment that has already been sent records the
                                            verdict; it does not reverse it.
                                        </p>
                                    </div>

                                    {decisionError && <ErrorBox failure={decisionError} />}

                                    {decisionMessage && (
                                        <div className="summary gap-above-sm">
                                            <div className="summary-title">
                                                Result
                                            </div>
                                            <ul>
                                                <li>{decisionMessage}</li>
                                            </ul>
                                        </div>
                                    )}
                                </>
                            )}
                        </section>
                    </main>
                </div>
            </div>
        </div>
    );
}
