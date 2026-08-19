import { useEffect, useMemo, useState, type ReactNode } from 'react';
import {
    fetchAlerts,
    fetchAlertDetail,
    formatMoney,
    parseAmount,
    postFraudDecision,
    type AlertCounters,
    type AlertDetail,
    type AlertFilters,
    type AlertQueueItem,
    type FraudDecision,
    type HistoryItem,
    type Page,
} from './api';
import { amountRangeProblem } from '@shared/alertFilters';
import { describeApiErrorLines } from '@shared/apiErrors';
import {
    FIELD_LABEL,
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
    CUSTOMER_HISTORY_TITLE,
    alertStateLabel,
    alertStateTone,
    authMethodLabel,
    decisionActionLabel,
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
 * Which amount bound is being typed. The two boxes are one control by role, so one function
 * handles both and this tells them apart.
 */
type AmountBound = 'min' | 'max';

/** Nobody has taken the case. Not an absent value: a card is not a data table. */
const UNASSIGNED = 'unassigned';

/**
 * The five history fields the table gives a column of its own, and the classes that size and
 * align them. The sixth, the decline reason, is prose and gets a row of its own under the payment
 * it explains rather than a sixth column 60px wide.
 *
 * The amount is marked where the cell is built. Counting header cells to find the column to right
 * align, which is what the customer application's stylesheet still does, breaks silently the day
 * a column is added.
 *
 * The route is two account numbers stacked in one column and still carries no cell class: what
 * has to be styled there is each of the two lines, and each is marked where it is built, for the
 * same reason the amount is.
 */
const HISTORY_COLUMNS: readonly { field: HistoryField; col: string; cell?: string }[] = [
    { field: 'id', col: 'col--id' },
    { field: 'createdAt', col: 'col--created' },
    { field: 'amount', col: 'col--amount', cell: 'cell--amount' },
    { field: 'status', col: 'col--status' },
    { field: 'route', col: 'col--route' },
];

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
 * `alertedIban` is the account the open alert was raised on, and the table now holds the payments
 * of every account the customer keeps. Without the mark an analyst reads ten rows and has to
 * match each source against the account printed in Facts by eye, which is the work the table was
 * meant to save. Compared by value and not normalised: both strings come out of the same column
 * of the same response, so they agree in case and spacing by construction.
 */
function historyCells(h: HistoryItem, alertedIban: string | null): HistoryRowCells<ReactNode> {
    const alerted = alertedIban !== null && h.fromIban === alertedIban;

    return {
        id: formatTransferId(h.id),
        createdAt: formatDateTime(h.createdAt),
        amount: formatMoney(h.amount),
        status: (
            <span className={`tone-${transferStatusTone(h.status)}`}>
                {transferStatusLabel(h.status, 'analyst')}
            </span>
        ),
        // Source above beneficiary: the money reads down the cell, and the number the eye is
        // hunting sits on the strong line. The mark is ink and weight rather than a glyph,
        // because a customer whose payments all leave one account gets it on every row, and a
        // sign repeated down a whole column stops being a sign. It reaches a screen reader as
        // the hidden phrase and nowhere else.
        route: (
            <>
                <span className={alerted ? 'route-from route-from--alerted' : 'route-from'}>
                    {alerted && <span className="visually-hidden">Alerted account: </span>}
                    {formatIban(h.fromIban)}
                </span>
                <span className="route-to">{formatIban(h.toIban)}</span>
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
 * A refusal, in the sentences the shared table answers with.
 *
 * A line each rather than one run of prose: the table sends what happened and, where there is one,
 * the thing to do about it, and the second is the half a reader acts on. The box around them is
 * the .error this window has always drawn.
 */
function ErrorBox(props: { lines: string[] }) {
    return (
        <div className="error">
            {props.lines.map(line => <div key={line}>{line}</div>)}
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

    const [selectedId, setSelectedId] = useState<number | null>(null);
    const [detail, setDetail] = useState<AlertDetail | null>(null);

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

    // Three lists of sentences rather than three strings: the shared error table answers with a
    // statement of what happened and, where there is one, the thing to do about it, and joining
    // them into one line is the caller's choice rather than the table's.
    const [listErr, setListErr] = useState<string[] | null>(null);
    const [detailErr, setDetailErr] = useState<string[] | null>(null);
    const [decisionErr, setDecisionErr] = useState<string[] | null>(null);
    // This desk had no success state at all: after a decision the alert left the NEW-filtered
    // queue, the panel unmounted with it, and the analyst was left with an empty pane and no
    // statement of what had happened.
    const [decisionMsg, setDecisionMsg] = useState<string | null>(null);
    const [busyList, setBusyList] = useState(false);
    const [busyMore, setBusyMore] = useState(false);
    const [busyDetail, setBusyDetail] = useState(false);
    const [busyDecision, setBusyDecision] = useState(false);

    const [decisionReason, setDecisionReason] = useState('');
    const [notes, setNotes] = useState('');

    const selected = useMemo(() => alerts.find(a => a.id === selectedId) || null, [alerts, selectedId]);

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
            setListErr([problem]);
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
                setSelectedId(null);
                setDetail(null);
            }
        } catch (e) {
            setListErr(describeApiErrorLines(e, 'alert-queue'));
        } finally {
            setBusyList(false);
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
            setListErr(describeApiErrorLines(e, 'alert-queue'));
        } finally {
            setBusyMore(false);
        }
    }

    async function openDetail(id: number) {
        setSelectedId(id);
        setDetail(null);
        setDecisionReason('');
        setNotes('');
        setDecisionMsg(null);
        setDecisionErr(null);
        try {
            setBusyDetail(true);
            setDetailErr(null);
            const d = await fetchAlertDetail(id);
            setDetail(d);
        } catch (e) {
            setDetailErr(describeApiErrorLines(e, 'alert-details'));
        } finally {
            setBusyDetail(false);
        }
    }

    async function decide(kind: FraudDecision) {
        if (!selectedId) return;
        try {
            setBusyDecision(true);
            setDecisionErr(null);
            setDecisionMsg(null);
            const updated = await postFraudDecision(selectedId, {
                decision: kind,
                reason: decisionReason.trim() || undefined,
                notes: notes.trim() || undefined,
                // Assignee and tags are carried over from the alert being decided.
                assignee: detail?.alert.assignee || undefined,
                tags: detail?.alert.tags || undefined,
            });
            setDetail(updated);
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
            // Re-read before reporting. This desk has no refresh control - reloadList runs
            // on mount and on a filter change and nowhere else - so telling the analyst to
            // reload would name something the UI does not offer, and the shared table's
            // sentence for a lost race promises the alert has been reloaded.
            await reloadList(true);
            try {
                setDetail(await fetchAlertDetail(selectedId));
            } catch {
                // The alert may no longer be readable; the message does not depend on it.
            }

            // The four branches written out here disagreed with the four the customer
            // application's desk wrote for the same four codes, and neither of them had a
            // sentence for ALERT_CHANGED, which is exactly what an analyst gets when their
            // verdict loses a race. One table, twenty codes, both desks.
            setDecisionErr(describeApiErrorLines(e, 'alert-decision'));
        } finally {
            setBusyDecision(false);
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
                                    <input placeholder="name" value={filters.assignee ?? ''} onChange={(e) => setF('assignee', e.target.value)} />
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

                            {listErr && <ErrorBox lines={listErr} />}
                            {busyList && <div className="hint">Loading…</div>}

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
                                {!busyList && alerts.length === 0 && <div className="hint">No alerts</div>}

                                {/*
                                  The last item of the tray, so the way to lengthen the queue is
                                  where the queue runs out. Removed rather than disabled once
                                  everything is on screen: a control that can do nothing still
                                  invites the press that proves it, and the line in the counters
                                  strip says so in words instead.
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
                              These three count the WHOLE queue, not the list above them, and
                              the server means it that way: it counts before applying any
                              filter. That is the right design and the numbers were never
                              wrong - what was missing is this sentence. New: 7 sitting over a
                              list of three reads as a contradiction unless the screen says
                              which number is which.

                              Counting the visible list instead would be worse: the desk opens
                              filtered to new alerts, so two of the three would be permanently
                              zero, and watching confirmed fraud rise as you work is the whole
                              point of having them.

                              The three states are the only three, so their sum is the queue.
                            */}
                            {counters && (
                                <div className="counters">
                                    <div>
                                        Whole queue,{' '}
                                        {counters.newCount + counters.suspiciousCount + counters.okCount}:
                                    </div>
                                    <div>{alertStateLabel('NEW')}: {counters.newCount}</div>
                                    <div>{alertStateLabel('SUSPICIOUS')}: {counters.suspiciousCount}</div>
                                    <div>{alertStateLabel('OK')}: {counters.okCount}</div>
                                    {/*
                                      How much of the FILTERED list is on screen, which is the one
                                      number in this strip that is not the whole queue. It takes
                                      the shared string verbatim, capital S and no full stop, so
                                      the cell reads like the four labels beside it and like the
                                      same line at the foot of the customer application's lists.
                                    */}
                                    <div>{showingLine(alerts.length, lastPage?.total ?? 0)}</div>
                                </div>
                            )}
                        </div>
                    </div>

                    {/* RIGHT: detail */}
                    <div className="right">
                        <div className="panel">
                            <div className="panel-title">Alert Detail: Review Suspicious Transaction</div>

                            <div className="panel-scroll">

                                {!selected && <div className="hint">Select an alert on the left.</div>}

                                {detailErr && <ErrorBox lines={detailErr} />}
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
                                                    <dd>{formatIban(detail.transfer.toIban)}</dd>
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
                                                </dl>
                                            </div>
                                        </div>

                                        <div className="box box--history">
                                            <div className="box-title">{CUSTOMER_HISTORY_TITLE}</div>
                                            {detail.history.length === 0 ? (
                                                <div className="hint">No history</div>
                                            ) : (
                                                <table className="history">
                                                    <colgroup>
                                                        {HISTORY_COLUMNS.map(c => (
                                                            <col key={c.field} className={c.col} />
                                                        ))}
                                                    </colgroup>
                                                    <thead>
                                                        <tr>
                                                            {HISTORY_COLUMNS.map(c => (
                                                                <th key={c.field} scope="col">{FIELD_LABEL[c.field]}</th>
                                                            ))}
                                                        </tr>
                                                    </thead>
                                                    {detail.history.map(h => {
                                                        const cells = historyCells(h, detail.transfer.fromIban);
                                                        return (
                                                            /* One row group per payment, so the
                                                               reason a payment was refused stays
                                                               part of the row it explains. */
                                                            <tbody key={h.id}>
                                                                <tr>
                                                                    {HISTORY_COLUMNS.map(c => (
                                                                        <td key={c.field} className={c.cell}>
                                                                            {cells[c.field]}
                                                                        </td>
                                                                    ))}
                                                                </tr>
                                                                {h.declineReason && (
                                                                    <tr className="history-note">
                                                                        <td colSpan={HISTORY_COLUMNS.length}>
                                                                            {FIELD_LABEL.declineReason}:{' '}
                                                                            {cells.declineReason}
                                                                        </td>
                                                                    </tr>
                                                                )}
                                                            </tbody>
                                                        );
                                                    })}
                                                </table>
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
                                    <div className="row row--2">
                                        <label>Reason</label>
                                        <input value={decisionReason} onChange={(e) => setDecisionReason(e.target.value)} placeholder="optional reason" />
                                    </div>
                                    <div className="row row--2">
                                        <label>Notes</label>
                                        <input value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="internal notes" />
                                    </div>

                                    {decisionErr && <ErrorBox lines={decisionErr} />}
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
                                        verdict; it does not reverse it.
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
