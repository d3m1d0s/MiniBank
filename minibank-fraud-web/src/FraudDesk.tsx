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

function fmt(dt?: string | null) {
    if (!dt) return '';
    const d = new Date(dt);
    return Number.isNaN(d.getTime()) ? dt : d.toLocaleString();
}

export default function FraudDesk(props: { username: string; onLogout: () => void }) {
    const [filters, setFilters] = useState<AlertFilters>({ state: 'NEW' });
    const [alerts, setAlerts] = useState<AlertQueueItem[]>([]);
    const [counters, setCounters] = useState<AlertCounters | null>(null);

    const [selectedId, setSelectedId] = useState<number | null>(null);
    const [detail, setDetail] = useState<AlertDetail | null>(null);

    const [listErr, setListErr] = useState<string | null>(null);
    const [detailErr, setDetailErr] = useState<string | null>(null);
    const [decisionErr, setDecisionErr] = useState<string | null>(null);
    const [busyList, setBusyList] = useState(false);
    const [busyDetail, setBusyDetail] = useState(false);
    const [busyDecision, setBusyDecision] = useState(false);

    const [decisionReason, setDecisionReason] = useState('');
    const [notes, setNotes] = useState('');

    const selected = useMemo(() => alerts.find(a => a.id === selectedId) || null, [alerts, selectedId]);

    useEffect(() => { void reloadList(); }, [filters]);

    async function reloadList() {
        try {
            setBusyList(true);
            setListErr(null);
            const resp = await fetchAlerts(filters);
            setAlerts(resp.items);
            setCounters(resp.counters);
            if (selectedId && !resp.items.some(x => x.id === selectedId)) {
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
            const updated = await postFraudDecision(selectedId, {
                decision: kind,
                reason: decisionReason.trim() || undefined,
                notes: notes.trim() || undefined,
                // Assignee and tags are carried over from the alert being decided.
                assignee: detail?.alert.assignee || undefined,
                tags: detail?.alert.tags || undefined,
            });
            setDetail(updated);
            await reloadList();
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
            await reloadList();
            try {
                setDetail(await fetchAlertDetail(selectedId));
            } catch {
                // The alert may no longer be readable; the message does not depend on it.
            }

            setDecisionErr(
                // The only 409 this endpoint can produce comes from Transfer.decline
                // refusing an already-sent transfer. FraudAlert.approve and markSuspicious
                // have no state guard at all, so "this alert was already decided" is not a
                // fact the server can report - do not claim it here.
                err.code === 'CONFLICT'
                    ? 'This transfer has already been sent, so the decision can no longer be applied.'
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
                                    <input placeholder="min" value={filters.minAmount ?? ''} onChange={(e) => setF('minAmount', e.target.value)} />
                                    <input placeholder="max" value={filters.maxAmount ?? ''} onChange={(e) => setF('maxAmount', e.target.value)} />
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
                                            <div className="li-state">{a.state}</div>
                                        </div>
                                        <div className="li-mid">{a.transferCode} • {a.amount} {a.currency}</div>
                                        <div className="li-bot">{a.shortReason}</div>
                                    </button>
                                ))}
                                {!busyList && alerts.length === 0 && <div className="hint">No alerts</div>}
                            </div>

                            {counters && (
                                <div className="counters">
                                    <div>NEW: {counters.newCount}</div>
                                    <div>SUSPICIOUS: {counters.suspiciousCount}</div>
                                    <div>OK: {counters.okCount}</div>
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

                                            <div className="actions">
                                                <button className="btn btn--primary" disabled={busyDecision} onClick={() => decide('APPROVE')}>Approve</button>
                                                <button className="btn" disabled={busyDecision} onClick={() => decide('DECLINE')}>Decline</button>
                                                <button className="btn" disabled={busyDecision} onClick={() => decide('REQUEST_CONFIRMATION')}>Request confirmation</button>
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
