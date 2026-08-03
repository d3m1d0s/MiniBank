import { useEffect, useMemo, useState } from 'react';
import {
    fetchAlerts,
    fetchAlertDetail,
    postFraudDecision,
    type AlertCounters,
    type AlertDetail,
    type AlertFilters,
    type AlertQueueItem,
    type ApiError,
    type FraudDecision,
} from './api';
import { amountRangeProblem } from './alertFilters';

function fmt(dt?: string | null) {
    if (!dt) return '';
    const d = new Date(dt);
    return Number.isNaN(d.getTime()) ? dt : d.toLocaleString();
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

    if (kind === 'APPROVE') {
        return status === 'WAITING_AUTH'
            ? 'Alert cleared. The payment is released to the customer to confirm; no money has moved.'
            : `Alert cleared. The transfer was already ${status}, so there was nothing to release.`;
    }

    if (kind === 'DECLINE') {
        return status === 'SENT'
            ? 'Recorded as confirmed fraud. The payment had already been sent and has NOT been reversed.'
            : `Alert marked suspicious and the transfer is ${status}.`;
    }

    return 'Notes, assignee and tags saved. No decision was taken: the alert is still open and the transfer is unchanged.';
}

export default function FraudDesk(props: { username: string; onLogout: () => void }) {
    const [filters, setFilters] = useState<AlertFilters>({ state: 'NEW' });
    const [alerts, setAlerts] = useState<AlertQueueItem[]>([]);
    const [counters, setCounters] = useState<AlertCounters | null>(null);

    const [selectedId, setSelectedId] = useState<number | null>(null);
    const [detail, setDetail] = useState<AlertDetail | null>(null);

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

    // unreadable belongs in here beside filters. Typing something the box cannot read into an
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
     * Records whether the browser could read what was typed into an amount box.
     *
     * A number input reports an unreadable value as the empty string, which is exactly what a
     * cleared box reports, so without this the parameter would simply not be sent and the
     * analyst would get the whole queue looking like a filtered one. Kept per box: fixing the
     * upper bound must not silently forgive the lower one.
     */
    function setAmountReadable(which: 'min' | 'max', input: HTMLInputElement) {
        setUnreadable(prev => ({ ...prev, [which]: input.validity.badInput }));
    }

    return (
        <div className="shell">
            <div className="window">
                <div className="titlebar">
                    <div className="title">MiniBank Fraud Desk</div>
                    <div className="titlebar-right">
                        <div className="user">{props.username}</div>
                        <button className="btn" onClick={props.onLogout}>Logout</button>
                    </div>
                </div>

                <div className="content content--split">
                    {/* LEFT: queue */}
                    <div className="left">
                        <div className="panel">
                            <div className="panel-title">Alerts Queue</div>

                            <div className="filters">
                                <div className="row row--2">
                                    <label>State</label>
                                    <select value={filters.state ?? ''} onChange={(e) => setF('state', e.target.value)}>
                                        <option value="">All</option>
                                        <option value="NEW">NEW</option>
                                        <option value="SUSPICIOUS">SUSPICIOUS</option>
                                        <option value="OK">OK</option>
                                    </select>
                                </div>

                                <div className="row row--3">
                                    <label>Amount</label>
                                    {/*
                                      Numeric, with a floor, because the queue prints amounts
                                      plainly - 1500.00 - and that is what an analyst copies in
                                      here. The one thing a number box must not be allowed to do
                                      quietly is report unreadable input as empty; that is what
                                      setAmountReadable is for.
                                    */}
                                    <input
                                        type="number"
                                        min="0"
                                        step="0.01"
                                        inputMode="decimal"
                                        placeholder="min"
                                        value={filters.minAmount ?? ''}
                                        onChange={(e) => {
                                            setAmountReadable('min', e.currentTarget);
                                            setF('minAmount', e.currentTarget.value);
                                        }}
                                    />
                                    <input
                                        type="number"
                                        min="0"
                                        step="0.01"
                                        inputMode="decimal"
                                        placeholder="max"
                                        value={filters.maxAmount ?? ''}
                                        onChange={(e) => {
                                            setAmountReadable('max', e.currentTarget);
                                            setF('maxAmount', e.currentTarget.value);
                                        }}
                                    />
                                </div>

                                <div className="row row--2">
                                    <label>Assignee</label>
                                    <input placeholder="name" value={filters.assignee ?? ''} onChange={(e) => setF('assignee', e.target.value)} />
                                </div>
                            </div>

                            {listErr && <div className="error">{listErr}</div>}
                            {busyList && <div className="hint">Loading…</div>}

                            <div className="list">
                                {alerts.map(a => (
                                    <button
                                        key={a.id}
                                        className={'list-item' + (a.id === selectedId ? ' list-item--active' : '')}
                                        onClick={() => openDetail(a.id)}
                                    >
                                        <div className="li-top">
                                            <div className="li-code">{a.alertCode}</div>
                                            {/* The transfer's status next to the alert's: an
                                                alert on money that has already gone used to
                                                look exactly like one on money still held. */}
                                            <div className="li-state">{a.state} • {a.transferStatus}</div>
                                        </div>
                                        <div className="li-mid">{a.transferCode} • {a.amount} {a.currency}</div>
                                        <div className="li-bot">{a.shortReason}</div>
                                    </button>
                                ))}
                                {!busyList && alerts.length === 0 && <div className="hint">No alerts</div>}
                            </div>

                            {/*
                              These three count the WHOLE queue, not the list above them, and
                              the server means it that way: it counts before applying any
                              filter. That is the right design and the numbers were never
                              wrong - what was missing is this sentence. NEW: 7 sitting over a
                              list of three reads as a contradiction unless the screen says
                              which number is which.

                              Counting the visible list instead would be worse: the desk opens
                              filtered to NEW, so two of the three would be permanently zero,
                              and watching SUSPICIOUS rise as you work is the whole point of
                              having them.

                              The three states are the only three, so their sum is the queue.
                            */}
                            {counters && (
                                <div className="counters">
                                    <div>
                                        Whole queue,{' '}
                                        {counters.newCount + counters.suspiciousCount + counters.okCount}:
                                    </div>
                                    <div>NEW: {counters.newCount}</div>
                                    <div>SUSPICIOUS: {counters.suspiciousCount}</div>
                                    <div>OK: {counters.okCount}</div>
                                    <div>showing {alerts.length}</div>
                                </div>
                            )}
                        </div>
                    </div>

                    {/* RIGHT: detail */}
                    <div className="right">
                        <div className="panel">
                            <div className="panel-title">Alert Detail — Review Suspicious Transaction</div>

                            <div className="panel-scroll">

                                {!selected && <div className="hint">Select an alert on the left.</div>}

                                {detailErr && <div className="error">{detailErr}</div>}
                                {busyDetail && <div className="hint">Loading detail…</div>}

                                {detail && !busyDetail && (
                                    <>
                                        <div className="box">
                                            <div><b>Transfer:</b> {detail.transfer.code}</div>
                                            <div><b>Status:</b> {detail.transfer.status}</div>
                                        </div>

                                        <div className="box">
                                            <div><b>Alert state:</b> {detail.alert.state}</div>
                                            <div><b>Risk score:</b> {detail.alert.riskScore ?? '—'}</div>
                                            <div><b>Created:</b> {fmt(detail.alert.createdAt)}</div>
                                        </div>

                                        <div className="box">
                                            <div className="box-title">Facts</div>
                                            <ul className="facts">
                                                <li><b>From:</b> {detail.transfer.fromIban} (balance {detail.transfer.fromBalance})</li>
                                                <li><b>To:</b> {detail.transfer.toIban}</li>
                                                <li><b>Amount:</b> {detail.transfer.amount} {detail.transfer.currency}</li>
                                                <li><b>Fee:</b> {detail.transfer.feeAmount}</li>
                                                <li><b>Time:</b> {fmt(detail.transfer.createdAt)}</li>
                                                <li><b>Auth:</b> {detail.transfer.authMethod ?? '—'}</li>
                                                <li><b>Reason:</b> {detail.alert.reason}</li>
                                            </ul>
                                        </div>

                                        <div className="box">
                                            <div className="box-title">Customer & Transfer history (last 10)</div>
                                            <div className="history">
                                                {detail.history.map(h => (
                                                    <div key={h.id} className="history-row">
                                                        <div>{fmt(h.createdAt)}</div>
                                                        <div>{h.amount} {h.currency}</div>
                                                        <div>{h.status}</div>
                                                        <div>{h.toIban}</div>
                                                    </div>
                                                ))}
                                                {detail.history.length === 0 && <div className="hint">No history</div>}
                                            </div>
                                        </div>

                                        <div className="box">
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
                                            {decisionMsg && <div className="hint">{decisionMsg}</div>}

                                            {/* The buttons mirror the domain guards exactly, so
                                                a click the server would refuse - taking the
                                                typed notes down with it - is not reachable.
                                                Approve only from NEW; Decline from anything but
                                                SUSPICIOUS, which is what lets fraud confirmed
                                                after the money left be recorded on an alert
                                                that had already been cleared. */}
                                            <div className="actions">
                                                <button
                                                    className="btn btn--primary"
                                                    disabled={busyDecision || detail.alert.state !== 'NEW'}
                                                    onClick={() => decide('APPROVE')}
                                                >Approve: release to customer</button>
                                                <button
                                                    className="btn"
                                                    disabled={busyDecision || detail.alert.state === 'SUSPICIOUS'}
                                                    onClick={() => decide('DECLINE')}
                                                >Decline: record fraud</button>
                                                <button
                                                    className="btn"
                                                    disabled={busyDecision}
                                                    onClick={() => decide('REQUEST_CONFIRMATION')}
                                                >Save notes, no decision</button>
                                            </div>

                                            <div className="hint">
                                                Approving does not send the money: it releases the
                                                payment for the customer to confirm. Declining a
                                                payment that has already been sent records the
                                                verdict; it does not reverse it.
                                            </div>
                                        </div>
                                    </>
                                )}

                            </div>


                        </div>
                    </div>

                </div>
            </div>
        </div>
    );
}
