// src/FraudDeskPage.tsx

import { useEffect, useState, type ReactNode } from 'react';
import './App.css';
import {
    fetchAlerts,
    fetchAlertDetail,
    postFraudDecision,
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
import { parseAmount } from '@shared/money';
import { describeApiErrorLines } from '@shared/apiErrors';
import {
    CUSTOMER_HISTORY_TITLE,
    alertStateLabel,
    alertStateTone,
    authMethodLabel,
    bankBoundaryMark,
    bankBoundaryLabel,
    decisionActionLabel,
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

    return {
        id: formatTransferId(h.id),
        createdAt: formatDateTime(h.createdAt),
        amount: formatMoney(h.amount),
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

export default function FraudDeskPage({ role, brand, identity, onNavigate }: Props) {
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

    const [listError, setListError] = useState<string[] | null>(null);
    const [detailError, setDetailError] = useState<string[] | null>(null);
    const [decisionError, setDecisionError] = useState<string[] | null>(null);
    const [decisionMessage, setDecisionMessage] = useState<string | null>(null);

    const [loadingList, setLoadingList] = useState(false);
    const [loadingMore, setLoadingMore] = useState(false);
    const [loadingDetail, setLoadingDetail] = useState(false);
    const [loadingDecision, setLoadingDecision] = useState(false);

    const [decisionReason, setDecisionReason] = useState('');
    const [decisionNotes, setDecisionNotes] = useState('');

    useEffect(() => {
        void loadAlerts();
        // eslint-disable-next-line react-hooks/exhaustive-deps
        // amountReason belongs in here beside filters. Typing something the parser cannot read
        // into an already empty box leaves the filters untouched, so on filters alone nothing
        // would re-run and the analyst would be told nothing at all.
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
            setCounters(null);
            setListError([problem]);
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

            // If the currently selected alert disappeared from the list, reset selection and details
            if (!keepSelection && selectedId && !resp.alerts.items.some((a) => a.id === selectedId)) {
                setSelectedId(null);
                setDetail(null);
            }
        } catch (e) {
            setListError(describeApiErrorLines(e, 'alert-queue'));
        } finally {
            setLoadingList(false);
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
            setListError(describeApiErrorLines(e, 'alert-queue'));
        } finally {
            setLoadingMore(false);
        }
    }

    async function handleSelect(id: number) {
        setSelectedId(id);
        setDetail(null);
        setDecisionMessage(null);
        setDecisionError(null);

        try {
            setLoadingDetail(true);
            setDetailError(null);
            const d = await fetchAlertDetail(id);
            setDetail(d);
        } catch (e) {
            setDetailError(describeApiErrorLines(e, 'alert-details'));
        } finally {
            setLoadingDetail(false);
        }
    }

    async function handleDecision(kind: FraudDecision) {
        if (!selectedId || !detail) return;

        const payload: FraudDecisionRequest = {
            decision: kind,
            reason: decisionReason.trim() || undefined,
            notes: decisionNotes.trim() || undefined,
            assignee: detail.alert.assignee || undefined,
            tags: detail.alert.tags,
        };

        try {
            setLoadingDecision(true);
            setDecisionError(null);

            const updated = await postFraudDecision(selectedId, payload);
            setDetail(updated);
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

            setDecisionError(describeApiErrorLines(e, 'alert-decision'));
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

                                <div className="filter-field">
                                    <label className="field-label" htmlFor="filter-assignee">
                                        Assignee
                                    </label>
                                    <input
                                        id="filter-assignee"
                                        className="field-input field-input--login"
                                        type="text"
                                        placeholder="e.g. analyst1"
                                        value={filters.assignee ?? ''}
                                        onChange={(e) =>
                                            updateFilter(
                                                'assignee',
                                                e.target.value,
                                            )
                                        }
                                    />
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

                            {listError && (
                                <div className="summary summary--danger gap-above-sm">
                                    <div className="summary-title">Error</div>
                                    <ul>
                                        {listError.map((line) => (
                                            <li key={line}>{line}</li>
                                        ))}
                                    </ul>
                                </div>
                            )}

                            {loadingList ? (
                                <p className="helper-text">
                                    Loading alerts…
                                </p>
                            ) : alerts.length === 0 ? (
                                <p className="helper-text">
                                    No fraud alerts.
                                </p>
                            ) : (
                                <>
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
                                </>
                            )}
                        </section>

                        {/* Details of the selected alert */}
                        <section className="section">
                            <h2 className="section-title">Alert details</h2>

                            {detailError && (
                                <div className="summary summary--danger gap-below-sm">
                                    <div className="summary-title">Error</div>
                                    <ul>
                                        {detailError.map((line) => (
                                            <li key={line}>{line}</li>
                                        ))}
                                    </ul>
                                </div>
                            )}

                            {!detail && !loadingDetail && (
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
                                            <span className="fact-label">Assignee:</span>{' '}
                                            <span className="fact-value">
                                                {detail.alert.assignee || 'unassigned'}
                                            </span>
                                        </p>
                                        <p>
                                            <span className="fact-label">Alert raised:</span>{' '}
                                            <span className="fact-value">
                                                {formatDateTime(detail.alert.createdAt)}
                                            </span>
                                        </p>
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
                                        {detail.history.length === 0 ? (
                                            <p className="helper-text">
                                                No history.
                                            </p>
                                        ) : (
                                            <div className="table-wrapper gap-above-sm">
                                                {/* Five columns from the shared field set, and
                                                    the sixth field under them rather than beside
                                                    them. The first was headed ID here and Payment
                                                    on the workstation, for the same column holding
                                                    the same TR-9; one word now, decided once.

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
                                                                {FIELD_LABEL[f]}
                                                            </th>
                                                        ))}
                                                    </tr>
                                                    </thead>
                                                    {detail.history.map((h) => {
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
                                            <label className="field-label">
                                                Reason / note for this decision
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

                                    {decisionError && (
                                        <div className="summary summary--danger gap-above-sm">
                                            <div className="summary-title">
                                                Error
                                            </div>
                                            <ul>
                                                {decisionError.map((line) => (
                                                    <li key={line}>{line}</li>
                                                ))}
                                            </ul>
                                        </div>
                                    )}

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
