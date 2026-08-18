import { useEffect, useMemo, useState, type ReactNode } from 'react';
import {
    fetchAlerts,
    fetchAlertDetail,
    formatMoney,
    postFraudDecision,
    type AlertCounters,
    type AlertDetail,
    type AlertFilters,
    type AlertQueueItem,
    type ApiError,
    type FraudDecision,
    type HistoryItem,
} from './api';
import { amountRangeProblem } from '@shared/alertFilters';
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
    alertStateLabel,
    alertStateTone,
    authMethodLabel,
    describeDeclineReason,
    transferStatusLabel,
    transferStatusTone,
} from '@shared/glossary';

/** The transfer status a withdrawn payment ends in. */
const WITHDRAWN = 'DECLINED';

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
 */
const HISTORY_COLUMNS: readonly { field: HistoryField; col: string; cell?: string }[] = [
    { field: 'id', col: 'col--id' },
    { field: 'createdAt', col: 'col--created' },
    { field: 'amount', col: 'col--amount', cell: 'cell--amount' },
    { field: 'status', col: 'col--status' },
    { field: 'toIban', col: 'col--to' },
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
        state: alertStateLabel(a.state),
        transferStatus: transferStatusLabel(a.transferStatus, 'analyst'),
        amount: formatMoney(a.amount),
        shortReason: a.shortReason,
        // The card has no column headers, so the two triage fields carry their own word.
        riskScore: `Risk ${a.riskScore ?? NOT_RECORDED}`,
        assignee: a.assignee || UNASSIGNED,
        createdAt: formatDateTime(a.createdAt),
    };
}

function historyCells(h: HistoryItem): HistoryRowCells<ReactNode> {
    return {
        id: formatTransferId(h.id),
        createdAt: formatDateTime(h.createdAt),
        amount: formatMoney(h.amount),
        status: (
            <span className={`tone-${transferStatusTone(h.status)}`}>
                {transferStatusLabel(h.status, 'analyst')}
            </span>
        ),
        toIban: formatIban(h.toIban),
        declineReason: describeDeclineReason(h.declineReason),
    };
}

/**
 * Whether the person reading this screen writes a comma as the decimal point.
 *
 * Asked of the platform rather than kept as a list of locales: the only question is which of the
 * two readings of `1,234` they expect, and the platform already knows.
 */
function commaIsDecimalHere(): boolean {
    const locale = navigator.language || 'cs-CZ';
    const parts = new Intl.NumberFormat(locale).formatToParts(1234.5);
    return parts.find((p) => p.type === 'decimal')?.value === ',';
}

/**
 * A typed amount as the plain decimal the query wants: the empty string for an empty box, null
 * when it cannot be read at all.
 *
 * These two boxes were type="number", which refuses `10 001,00` - the exact string the queue
 * beside them prints for the amount being filtered to. A number input reads the browser's own
 * convention and reports anything else as an empty box, so the bound was silently dropped and the
 * whole queue came back looking like a filtered one.
 *
 * The reading is the one the customer application's money.ts already settled for the payment
 * field: spaces are noise, a mark that repeats is grouping, grouping runs in threes, and the one
 * genuinely ambiguous shape - a single mark with exactly three digits behind it - is decided by
 * the reader's own convention rather than guessed. It is written here because that parser sits in
 * the other application's source; the two are one rule and belong in one function, which is a
 * change to the shared layer rather than to this screen.
 */
function readAmount(typed: string): string | null {
    const compact = typed.replace(/\s/g, '');
    if (compact === '') {
        return '';
    }

    const negative = compact.startsWith('-');
    // Kept rather than refused, so that a negative bound is answered by the sentence naming it
    // and not by the one about digits.
    const body = negative ? compact.slice(1) : compact;
    if (!/^[0-9.,]+$/.test(body)) {
        return null;
    }

    const commas = (body.match(/,/g) ?? []).length;
    const dots = (body.match(/\./g) ?? []).length;

    let decimal: string | null;
    if (commas > 0 && dots > 0) {
        // Both kinds present, so one groups and the other divides, and the rightmost divides.
        decimal = body.lastIndexOf(',') > body.lastIndexOf('.') ? ',' : '.';
    } else if (commas + dots === 0 || commas > 1 || dots > 1) {
        // A mark that repeats cannot be the decimal point.
        decimal = null;
    } else {
        const mark = commas === 1 ? ',' : '.';
        const before = body.indexOf(mark);
        const after = body.length - before - 1;
        decimal =
            after === 3 && before > 0
                ? (commaIsDecimalHere() === (mark === ',') ? mark : null)
                : mark;
    }

    const cut = decimal === null ? body.length : body.lastIndexOf(decimal);
    const grouped = body.slice(0, cut);
    const fraction = decimal === null ? '' : body.slice(cut + 1);

    // Whatever is left of the decimal point is grouping, and grouping runs in threes, so a
    // mistyped 12.34.567 is refused rather than read as twelve million.
    if (/[.,]/.test(grouped)) {
        const groups = grouped.split(/[.,]/);
        const wellGrouped =
            groups[0].length >= 1 &&
            groups[0].length <= 3 &&
            groups.slice(1).every((g) => g.length === 3);
        if (!wellGrouped) {
            return null;
        }
    }

    const whole = grouped.replace(/[.,]/g, '');
    if (fraction.length > 2 || !/^\d*$/.test(fraction) || (whole === '' && fraction === '')) {
        return null;
    }

    const digits = fraction ? `${whole || '0'}.${fraction}` : whole;
    return negative ? `-${digits}` : digits;
}

/**
 * What actually happened, read off the alert the server sent back rather than off the button
 * that was pressed - a DECLINE on a payment that has already gone now succeeds and records the
 * verdict without stopping anything, so a message keyed on the label would say the money was
 * held when it is gone.
 *
 * Kept word for word in step with minibank-web's FraudDeskPage, so the two desks cannot
 * disagree about what a decision did.
 */
function describeDecision(kind: FraudDecision, updated: AlertDetail): string {
    const status = updated.transfer.status;
    const said = transferStatusLabel(status, 'analyst');

    if (kind === 'APPROVE') {
        return status === 'WAITING_AUTH'
            ? 'Alert cleared. The payment is released to the customer to confirm; no money has moved.'
            : `Alert cleared. The transfer was already ${said}, so there was nothing to release.`;
    }

    if (kind === 'DECLINE') {
        return status === 'SENT'
            ? 'Recorded as confirmed fraud. The payment had already been sent and has NOT been reversed.'
            : `Alert marked as confirmed fraud and the transfer is ${said}.`;
    }

    return 'Notes, assignee and tags saved. No decision was taken: the alert is still open and the transfer is unchanged.';
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

    const [selectedId, setSelectedId] = useState<number | null>(null);
    const [detail, setDetail] = useState<AlertDetail | null>(null);

    /**
     * What was typed into each amount box, kept beside the decimal the filter carries.
     *
     * Two states rather than one because they are two different things: the analyst sees what
     * they wrote, in whatever convention they wrote it, and the query carries the plain decimal
     * the server parses.
     */
    const [amountText, setAmountText] = useState({ min: '', max: '' });
    const [unreadable, setUnreadable] = useState({ min: false, max: false });

    const [listErr, setListErr] = useState<string | null>(null);
    const [detailErr, setDetailErr] = useState<string | null>(null);
    const [decisionErr, setDecisionErr] = useState<string | null>(null);
    // This desk had no success state at all: after a decision the alert left the NEW-filtered
    // queue, the panel unmounted with it, and the analyst was left with an empty pane and no
    // statement of what had happened.
    const [decisionMsg, setDecisionMsg] = useState<string | null>(null);
    const [busyList, setBusyList] = useState(false);
    const [busyDetail, setBusyDetail] = useState(false);
    const [busyDecision, setBusyDecision] = useState(false);

    const [decisionReason, setDecisionReason] = useState('');
    const [notes, setNotes] = useState('');

    const selected = useMemo(() => alerts.find(a => a.id === selectedId) || null, [alerts, selectedId]);

    // unreadable belongs in here beside filters. Typing something that cannot be read into an
    // already empty field leaves the value at '' and the filters untouched, so on filters alone
    // nothing would re-run and the analyst would be told nothing at all.
    useEffect(() => { void reloadList(); }, [filters, unreadable]);

    async function reloadList(keepSelection = false) {
        // Checked before the request, and the server checks it again. This half exists to name
        // which two numbers are the wrong way round; the server cannot, because no handler
        // echoes an exception message. The server half exists because the endpoint is reachable
        // without this screen.
        const problem = amountRangeProblem(filters, unreadable);
        if (problem) {
            setAlerts([]);
            setCounters(null);
            setListErr(problem);
            return;
        }

        try {
            setBusyList(true);
            setListErr(null);
            const resp = await fetchAlerts(filters);
            setAlerts(resp.items);
            setCounters(resp.counters);
            if (!keepSelection && selectedId && !resp.items.some(x => x.id === selectedId)) {
                setSelectedId(null);
                setDetail(null);
            }
        } catch (e) {
            setListErr((e as Error).message || 'Failed to load alerts');
        } finally {
            setBusyList(false);
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
            setDetailErr((e as Error).message || 'Failed to load detail');
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
            setDecisionMsg(describeDecision(kind, updated));
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
            const err = e as ApiError;

            // Re-read before reporting. This desk has no refresh control - reloadList runs
            // on mount and on a filter change and nowhere else - so telling the analyst to
            // reload would name something the UI does not offer.
            await reloadList(true);
            try {
                setDetail(await fetchAlertDetail(selectedId));
            } catch {
                // The alert may no longer be readable; the message does not depend on it.
            }

            setDecisionErr(
                // A 409 here now means the alert was already decided by somebody else, or the
                // transfer moved out from under the decision. Both are refused by a state guard
                // and neither is distinguishable in the body, so the wording names the one thing
                // certainly true and points at the refreshed panel. The sentence this replaced
                // named "already sent", which is now the one case that succeeds.
                err.code === 'CONFLICT'
                    ? 'This decision was not applied: the alert or its transfer has already changed state. The panel above has been refreshed.'
                    : err.code === 'NOT_FOUND'
                        ? 'This alert no longer exists.'
                        : err.code === 'VALIDATION_ERROR'
                            ? 'That decision was not accepted. Please try again.'
                            : err.message || 'Failed to apply the decision.',
            );
        } finally {
            setBusyDecision(false);
        }
    }

    function setF<K extends keyof AlertFilters>(k: K, v: string) {
        setFilters(prev => ({ ...prev, [k]: v || undefined }));
    }

    /**
     * Takes what was typed into an amount box and files it in both places.
     *
     * The box that cannot be read is recorded per box on purpose: fixing the upper bound must not
     * silently forgive the lower one, and a bound that is dropped without a word is the whole
     * queue dressed up as a filtered one.
     */
    function setAmountBound(which: 'min' | 'max', typed: string) {
        const decimal = readAmount(typed);
        setAmountText(prev => ({ ...prev, [which]: typed }));
        setUnreadable(prev => ({ ...prev, [which]: decimal === null }));
        setF(which === 'min' ? 'minAmount' : 'maxAmount', decimal ?? '');
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
                                        onChange={(e) => setAmountBound('min', e.target.value)}
                                    />
                                    <input
                                        inputMode="decimal"
                                        placeholder="max"
                                        value={amountText.max}
                                        onChange={(e) => setAmountBound('max', e.target.value)}
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

                            {listErr && <div className="error">{listErr}</div>}
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
                                    <div>showing {alerts.length}</div>
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

                                {detailErr && <div className="error">{detailErr}</div>}
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
                                              A definition list, and the two timestamps are named
                                              after the objects they belong to. They used to be
                                              "Created" in one block and "Time" in another, print
                                              identically in the demo data, and neither word said
                                              which of the alert and the payment it meant.
                                            */}
                                            <dl className="facts">
                                                <dt>From</dt>
                                                <dd>{formatIban(detail.transfer.fromIban)}</dd>
                                                {/* The account number and the balance behind it are
                                                    two facts; they used to share one line. */}
                                                <dt>From balance</dt>
                                                <dd className="num">{formatMoney(detail.transfer.fromBalance)}</dd>
                                                <dt>{FIELD_LABEL.toIban}</dt>
                                                <dd>{formatIban(detail.transfer.toIban)}</dd>
                                                <dt>Fee</dt>
                                                <dd className="num">{formatMoney(detail.transfer.feeAmount)}</dd>
                                                <dt>Auth method</dt>
                                                <dd>{authMethodLabel(detail.transfer.authMethod) || NOT_RECORDED}</dd>
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

                                        <div className="box box--history">
                                            <div className="box-title">Customer &amp; Transfer history (last 10)</div>
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
                                                        const cells = historyCells(h);
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

                                    {decisionErr && <div className="error">{decisionErr}</div>}
                                    {decisionMsg && <div className="result">{decisionMsg}</div>}

                                    {/* The buttons mirror the domain guards exactly, so
                                        a click the server would refuse - taking the
                                        typed notes down with it - is not reachable.
                                        Approve only from a new alert; Decline from
                                        anything not already recorded as fraud, which is
                                        what lets fraud confirmed after the money left be
                                        recorded on an alert that had already been cleared. */}
                                    <div className="actions">
                                        <button
                                            className="btn btn--primary"
                                            disabled={busyDecision || detail.alert.state !== 'NEW'}
                                            onClick={() => decide('APPROVE')}
                                        >Approve: release to customer</button>
                                        <button
                                            className="btn btn--danger"
                                            disabled={busyDecision || detail.alert.state === 'SUSPICIOUS'}
                                            onClick={() => decide('DECLINE')}
                                        >Decline: record fraud</button>
                                        {/* The token this posts was REQUEST_CONFIRMATION, which
                                            named something it has never done: it asks nobody for
                                            anything and takes no decision. */}
                                        <button
                                            className="btn btn--quiet"
                                            disabled={busyDecision}
                                            onClick={() => decide('ANNOTATE')}
                                        >Save notes, no decision</button>
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
