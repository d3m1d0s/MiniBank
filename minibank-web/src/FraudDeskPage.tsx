// src/FraudDeskPage.tsx

import { useEffect, useState } from 'react';
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
    type ApiError,
} from './api';
import { amountRangeProblem } from '@shared/alertFilters';
import { formatMoney } from './money';

/** The transfer status a withdrawn payment ends in. */
const WITHDRAWN = 'DECLINED';

// The fraud desk takes no navigation callback: only a FRAUD_ANALYST reaches it,
// and App restricts that role to this view.

function formatDate(value?: string | null): string {
    if (!value) return '';
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) return value;
    return d.toLocaleString();
}

/**
 * What actually happened, read off the alert the server sent back rather than off the button
 * that was pressed. Deriving it from the button was safe only while every decision did the one
 * thing its label said: a DECLINE on a payment that had already gone is now accepted and
 * records the verdict without stopping anything, and announcing "Transfer declined" for it
 * would tell the analyst the money was held when it is gone - a worse lie than the 409 it
 * replaced.
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

export default function FraudDeskPage() {
    const [alerts, setAlerts] = useState<AlertQueueItem[]>([]);
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

    const [unreadable, setUnreadable] = useState({ min: false, max: false });

    const [listError, setListError] = useState<string | null>(null);
    const [detailError, setDetailError] = useState<string | null>(null);
    const [decisionError, setDecisionError] = useState<string | null>(null);
    const [decisionMessage, setDecisionMessage] = useState<string | null>(null);

    const [loadingList, setLoadingList] = useState(false);
    const [loadingDetail, setLoadingDetail] = useState(false);
    const [loadingDecision, setLoadingDecision] = useState(false);

    const [decisionReason, setDecisionReason] = useState('');
    const [decisionNotes, setDecisionNotes] = useState('');

    useEffect(() => {
        void loadAlerts();
        // eslint-disable-next-line react-hooks/exhaustive-deps
        // unreadable belongs in here beside filters. Typing something the box cannot read into
        // an already empty field leaves the value at '' and the filters untouched, so on filters
        // alone nothing would re-run and the analyst would be told nothing at all.
    }, [filters, unreadable]);

    /**
     * Records whether the browser could read what was typed into an amount box.
     *
     * A number input reports an unreadable value as the empty string, which is exactly what a
     * cleared box reports, so without this the parameter would simply not be sent and the
     * analyst would get the whole queue looking like a filtered one. Kept per box: fixing the
     * upper bound must not silently forgive the lower one.
     */
    function setAmountReadable(which: 'min' | 'max', input: HTMLInputElement) {
        setUnreadable((prev) => ({ ...prev, [which]: input.validity.badInput }));
    }

    async function loadAlerts(keepSelection = false) {
        // Checked before the request, and the server checks it again. This half exists to name
        // which two numbers are the wrong way round; the server cannot, because no handler
        // echoes an exception message. The server half exists because the endpoint is reachable
        // without this screen.
        const problem = amountRangeProblem(filters, unreadable);
        if (problem) {
            setAlerts([]);
            setCounters(null);
            setListError(problem);
            return;
        }

        try {
            setLoadingList(true);
            setListError(null);
            const resp = await fetchAlerts(filters);
            setAlerts(resp.items);
            setCounters(resp.counters);

            // If the currently selected alert disappeared from the list, reset selection and details
            if (!keepSelection && selectedId && !resp.items.some((a) => a.id === selectedId)) {
                setSelectedId(null);
                setDetail(null);
            }
        } catch (e) {
            setListError((e as Error).message || 'Failed to load alerts.');
        } finally {
            setLoadingList(false);
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
            setDetailError(
                (e as Error).message || 'Failed to load alert details.',
            );
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
            setDecisionMessage(describeDecision(kind, updated));

            // Keeps the decided alert on screen. With the default NEW filter it leaves the
            // queue the moment it is decided, and clearing the selection would unmount the
            // panel that shows what the decision did.
            await loadAlerts(true);
        } catch (e) {
            const err = e as ApiError;

            // Re-read before reporting, so the panel matches the server. The 409 wording had to
            // change outright: it used to name "already sent", which is now the one case that
            // succeeds. What produces a 409 here is a state guard - the alert was decided by
            // somebody else, or the transfer moved out from under the decision - and neither is
            // distinguishable in the body, so the sentence names the one thing certainly true
            // and points at the refreshed panel. Kept identical to the wording in
            // minibank-fraud-web, so the two desks do not disagree.
            await loadAlerts(true);
            try {
                setDetail(await fetchAlertDetail(selectedId));
            } catch {
                // The alert may no longer be readable; the message does not depend on it.
            }

            setDecisionError(
                err.code === 'CONFLICT'
                    ? 'This decision was not applied: the alert or its transfer has already changed state. The panel above has been refreshed.'
                    : err.code === 'NOT_FOUND'
                        ? 'This alert no longer exists.'
                        : err.message || 'Failed to apply decision.',
            );
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
                    <h1>Fraud Desk</h1>
                </header>

                <div className="card-body layout">
                    {/* Left: main navigation for the fraud analyst workspace */}
                    <nav className="nav">
                        <div className="nav-title">Navigation</div>
                        <ul>
                            <li>
                                <button type="button" className="nav-link">
                                    Dashboard
                                </button>
                            </li>
                            <li>
                                <button type="button" className="nav-link">
                                    Settings
                                </button>
                            </li>
                            <li>
                                <button
                                    type="button"
                                    className="nav-link nav-link--active"
                                >
                                    Fraud desk
                                </button>
                            </li>
                        </ul>
                    </nav>

                    {/* Right: queue, alert details and decision controls */}
                    <main className="form-panel">
                        {/* Alerts queue */}
                        <section className="section">
                            <h2 className="section-title">Alerts queue</h2>

                            {counters && (
                                <div className="summary gap-below-sm">
                                    {/*
                                      "Overview" was nearly right and too vague to settle the
                                      question these numbers raise. They count the WHOLE queue,
                                      not the list below, and the server means it that way: it
                                      counts before applying any filter. New: 7 over a list of
                                      three reads as a contradiction until the screen says which
                                      number is which.

                                      Counting the visible list instead would be worse: this
                                      page opens filtered to New, so two of the three would be
                                      permanently zero, and watching Suspicious rise as you work
                                      is the whole point of having them.

                                      The three states are the only three, so their sum is the
                                      queue.
                                    */}
                                    <div className="summary-title">
                                        Whole queue,{' '}
                                        {counters.newCount +
                                            counters.suspiciousCount +
                                            counters.okCount}{' '}
                                        alerts
                                    </div>
                                    <ul>
                                        <li>New: {counters.newCount}</li>
                                        <li>
                                            Suspicious:{' '}
                                            {counters.suspiciousCount}
                                        </li>
                                        <li>OK: {counters.okCount}</li>
                                    </ul>
                                    <div>
                                        Showing {alerts.length} with the filters
                                        below.
                                    </div>
                                </div>
                            )}

                            {/* Filters for the queue */}
                            <div className="section-block">
                                <div className="field-row">
                                    <label className="field-label">
                                        State
                                    </label>
                                    <select
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
                                        <option value="NEW">New</option>
                                        <option value="SUSPICIOUS">
                                            Suspicious
                                        </option>
                                        <option value="OK">OK</option>
                                    </select>
                                </div>

                                <div className="field-row">
                                    <label className="field-label">
                                        Amount
                                    </label>
                                    {/*
                                      Numeric, with a floor, because the queue prints amounts
                                      plainly - 1500.00 - and that is what an analyst copies in
                                      here. The one thing a number box must not be allowed to do
                                      quietly is report unreadable input as empty; that is what
                                      setAmountReadable is for.
                                    */}
                                    <input
                                        className="field-input field-input--amount"
                                        type="number"
                                        min="0"
                                        step="0.01"
                                        inputMode="decimal"
                                        placeholder="Min"
                                        value={filters.minAmount ?? ''}
                                        onChange={(e) => {
                                            setAmountReadable('min', e.currentTarget);
                                            updateFilter('minAmount', e.currentTarget.value);
                                        }}
                                    />
                                    <div className="field-side">-</div>
                                    <input
                                        className="field-input field-input--amount"
                                        type="number"
                                        min="0"
                                        step="0.01"
                                        inputMode="decimal"
                                        placeholder="Max"
                                        value={filters.maxAmount ?? ''}
                                        onChange={(e) => {
                                            setAmountReadable('max', e.currentTarget);
                                            updateFilter('maxAmount', e.currentTarget.value);
                                        }}
                                    />
                                </div>

                                <div className="field-row">
                                    <label className="field-label">
                                        Assignee
                                    </label>
                                    <input
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

                                <div className="field-row">
                                    <label className="field-label">
                                        Withdrawn
                                    </label>
                                    <label>
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
                                        />{' '}
                                        Show alerts on cancelled payments
                                    </label>
                                </div>
                            </div>

                            {listError && (
                                <div className="summary summary--danger gap-above-sm">
                                    <div className="summary-title">Error</div>
                                    <ul>
                                        <li>{listError}</li>
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
                                <div className="table-wrapper gap-above-sm">
                                    <table className="table">
                                        <thead>
                                        <tr>
                                            <th>Alert</th>
                                            <th>Transfer</th>
                                            <th>State</th>
                                            {/* The transfer's status. An alert on money that
                                                has already gone used to look exactly like one
                                                on money still held. */}
                                            <th>Transfer status</th>
                                            <th>Amount</th>
                                            <th>Reason</th>
                                            <th>Risk</th>
                                            <th>Assignee</th>
                                            <th>Created</th>
                                        </tr>
                                        </thead>
                                        <tbody>
                                        {alerts.map((a) => (
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
                                                <td>{a.alertCode}</td>
                                                <td>{a.transferCode}</td>
                                                <td>{a.state}</td>
                                                <td>{a.transferStatus}</td>
                                                <td>{formatMoney(a.amount)}</td>
                                                <td>{a.shortReason}</td>
                                                <td>
                                                    {a.riskScore ?? '—'}
                                                </td>
                                                <td>
                                                    {a.assignee || '—'}
                                                </td>
                                                <td>
                                                    {formatDate(
                                                        a.createdAt,
                                                    )}
                                                </td>
                                            </tr>
                                        ))}
                                        </tbody>
                                    </table>
                                </div>
                            )}
                        </section>

                        {/* Details of the selected alert */}
                        <section className="section">
                            <h2 className="section-title">Alert details</h2>

                            {detailError && (
                                <div className="summary summary--danger gap-below-sm">
                                    <div className="summary-title">Error</div>
                                    <ul>
                                        <li>{detailError}</li>
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
                                    <div className="details-card">
                                        <p>
                                            <strong>Alert:</strong>{' '}
                                            {detail.alert.id} (
                                            {detail.alert.state})
                                        </p>
                                        <p>
                                            <strong>Reason:</strong>{' '}
                                            {detail.alert.reason}
                                        </p>
                                        <p>
                                            <strong>Risk score:</strong>{' '}
                                            {detail.alert.riskScore ?? '—'}
                                        </p>
                                        <p>
                                            <strong>Assignee:</strong>{' '}
                                            {detail.alert.assignee || '—'}
                                        </p>
                                        <p>
                                            <strong>Created:</strong>{' '}
                                            {formatDate(
                                                detail.alert.createdAt,
                                            )}
                                        </p>
                                    </div>

                                    <div className="details-card gap-above-lg">
                                        <p>
                                            <strong>Transfer:</strong>{' '}
                                            {detail.transfer.code}
                                        </p>
                                        <p>
                                            <strong>Status:</strong>{' '}
                                            {detail.transfer.status}
                                        </p>
                                        <p>
                                            <strong>From:</strong>{' '}
                                            {detail.transfer.fromIban} (Balance:{' '}
                                            {formatMoney(detail.transfer.fromBalance)})
                                        </p>
                                        <p>
                                            <strong>To:</strong>{' '}
                                            {detail.transfer.toIban}
                                        </p>
                                        <p>
                                            <strong>Amount:</strong>{' '}
                                            {formatMoney(detail.transfer.amount)}
                                        </p>
                                        <p>
                                            <strong>Fee:</strong>{' '}
                                            {formatMoney(detail.transfer.feeAmount)}
                                        </p>
                                        <p>
                                            <strong>Created:</strong>{' '}
                                            {formatDate(
                                                detail.transfer.createdAt,
                                            )}
                                        </p>
                                        <p>
                                            <strong>Auth method:</strong>{' '}
                                            {detail.transfer.authMethod || '—'}
                                        </p>
                                    </div>

                                    <div className="details-card gap-above-lg">
                                        <p>
                                            <strong>
                                                Customer history (last 10
                                                transfers from this account)
                                            </strong>
                                        </p>
                                        {detail.history.length === 0 ? (
                                            <p className="helper-text">
                                                No history.
                                            </p>
                                        ) : (
                                            <div className="table-wrapper gap-above-sm">
                                                <table className="table">
                                                    <thead>
                                                    <tr>
                                                        <th>ID</th>
                                                        <th>Created</th>
                                                        <th>Amount</th>
                                                        <th>Status</th>
                                                        <th>To</th>
                                                        <th>
                                                            Decline reason
                                                        </th>
                                                    </tr>
                                                    </thead>
                                                    <tbody>
                                                    {detail.history.map(
                                                        (h) => (
                                                            <tr
                                                                key={h.id}
                                                            >
                                                                <td>
                                                                    {h.id}
                                                                </td>
                                                                <td>
                                                                    {formatDate(
                                                                        h.createdAt,
                                                                    )}
                                                                </td>
                                                                <td>
                                                                    {formatMoney(
                                                                        h.amount,
                                                                    )}
                                                                </td>
                                                                <td>
                                                                    {
                                                                        h.status
                                                                    }
                                                                </td>
                                                                <td>
                                                                    {
                                                                        h.toIban
                                                                    }
                                                                </td>
                                                                <td>
                                                                    {h.declineReason ||
                                                                        '—'}
                                                                </td>
                                                            </tr>
                                                        ),
                                                    )}
                                                    </tbody>
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
                                            an alert that was already cleared. */}
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
                                                    : 'Approve: release to the customer'}
                                            </button>
                                            <button
                                                type="button"
                                                className="btn-secondary"
                                                disabled={
                                                    loadingDecision ||
                                                    detail.alert.state === 'SUSPICIOUS'
                                                }
                                                onClick={() =>
                                                    handleDecision('DECLINE')
                                                }
                                            >
                                                Decline: record confirmed fraud
                                            </button>
                                            <button
                                                type="button"
                                                className="btn-secondary"
                                                disabled={loadingDecision}
                                                onClick={() =>
                                                    handleDecision(
                                                        'REQUEST_CONFIRMATION',
                                                    )
                                                }
                                            >
                                                Save notes, no decision
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
                                                <li>{decisionError}</li>
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
