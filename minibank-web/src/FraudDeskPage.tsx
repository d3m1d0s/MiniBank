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
} from './api';

// The fraud desk takes no navigation callback: only a FRAUD_ANALYST reaches it,
// and App restricts that role to this view.

function formatDate(value?: string | null): string {
    if (!value) return '';
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) return value;
    return d.toLocaleString();
}

export default function FraudDeskPage() {
    const [alerts, setAlerts] = useState<AlertQueueItem[]>([]);
    const [counters, setCounters] = useState<AlertCounters | null>(null);
    const [selectedId, setSelectedId] = useState<number | null>(null);
    const [detail, setDetail] = useState<AlertDetail | null>(null);

    const [filters, setFilters] = useState<AlertFilters>({ state: 'NEW' });

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
    }, [filters]);

    async function loadAlerts() {
        try {
            setLoadingList(true);
            setListError(null);
            const resp = await fetchAlerts(filters);
            setAlerts(resp.items);
            setCounters(resp.counters);

            // If the currently selected alert disappeared from the list, reset selection and details
            if (selectedId && !resp.items.some((a) => a.id === selectedId)) {
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

            const msg =
                kind === 'APPROVE'
                    ? 'Transfer approved and fraud alert closed.'
                    : kind === 'DECLINE'
                        ? 'Transfer declined and fraud alert marked as suspicious.'
                        : 'Customer confirmation has been requested for this transfer.';

            setDecisionMessage(msg);

            await loadAlerts();
        } catch (e) {
            setDecisionError(
                (e as Error).message || 'Failed to apply decision.',
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
                                <div
                                    className="summary"
                                    style={{ marginBottom: 8 }}
                                >
                                    <div className="summary-title">
                                        Overview
                                    </div>
                                    <ul>
                                        <li>New: {counters.newCount}</li>
                                        <li>
                                            Suspicious:{' '}
                                            {counters.suspiciousCount}
                                        </li>
                                        <li>OK: {counters.okCount}</li>
                                    </ul>
                                </div>
                            )}

                            {/* Filters for the queue */}
                            <div className="section-block">
                                <div className="field-row">
                                    <label className="field-label">
                                        State
                                    </label>
                                    <select
                                        className="field-input"
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
                                    <input
                                        className="field-input"
                                        type="text"
                                        placeholder="Min"
                                        value={filters.minAmount ?? ''}
                                        onChange={(e) =>
                                            updateFilter(
                                                'minAmount',
                                                e.target.value,
                                            )
                                        }
                                    />
                                    <div className="field-side">-</div>
                                    <input
                                        className="field-input"
                                        type="text"
                                        placeholder="Max"
                                        value={filters.maxAmount ?? ''}
                                        onChange={(e) =>
                                            updateFilter(
                                                'maxAmount',
                                                e.target.value,
                                            )
                                        }
                                    />
                                </div>

                                <div className="field-row">
                                    <label className="field-label">
                                        Assignee
                                    </label>
                                    <input
                                        className="field-input"
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
                            </div>

                            {listError && (
                                <div
                                    className="summary"
                                    style={{
                                        borderColor: 'salmon',
                                        marginTop: 8,
                                    }}
                                >
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
                                <div
                                    className="table-wrapper"
                                    style={{ marginTop: 8 }}
                                >
                                    <table className="table">
                                        <thead>
                                        <tr>
                                            <th>Alert</th>
                                            <th>Transfer</th>
                                            <th>State</th>
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
                                                <td>
                                                    {a.amount}{' '}
                                                    {a.currency}
                                                </td>
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
                                <div
                                    className="summary"
                                    style={{
                                        borderColor: 'salmon',
                                        marginBottom: 8,
                                    }}
                                >
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

                                    <div
                                        className="details-card"
                                        style={{ marginTop: 12 }}
                                    >
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
                                            {detail.transfer.fromBalance})
                                        </p>
                                        <p>
                                            <strong>To:</strong>{' '}
                                            {detail.transfer.toIban}
                                        </p>
                                        <p>
                                            <strong>Amount:</strong>{' '}
                                            {detail.transfer.amount}{' '}
                                            {detail.transfer.currency}
                                        </p>
                                        <p>
                                            <strong>Fee:</strong>{' '}
                                            {detail.transfer.feeAmount}
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

                                    <div
                                        className="details-card"
                                        style={{ marginTop: 12 }}
                                    >
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
                                            <div
                                                className="table-wrapper"
                                                style={{ marginTop: 8 }}
                                            >
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
                                                                    {h.amount}{' '}
                                                                    {
                                                                        h.currency
                                                                    }
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

                                        <div
                                            className="field-column"
                                            style={{ marginTop: 8 }}
                                        >
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

                                        <div
                                            className="actions"
                                            style={{ marginTop: 12 }}
                                        >
                                            <button
                                                type="button"
                                                className="btn-primary"
                                                disabled={loadingDecision}
                                                onClick={() =>
                                                    handleDecision('APPROVE')
                                                }
                                            >
                                                {loadingDecision
                                                    ? 'Applying…'
                                                    : 'Approve transfer'}
                                            </button>
                                            <button
                                                type="button"
                                                className="btn-secondary"
                                                disabled={loadingDecision}
                                                onClick={() =>
                                                    handleDecision('DECLINE')
                                                }
                                            >
                                                Decline transfer
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
                                                Request customer confirmation
                                            </button>
                                        </div>
                                    </div>

                                    {decisionError && (
                                        <div
                                            className="summary"
                                            style={{
                                                borderColor: 'salmon',
                                                marginTop: 8,
                                            }}
                                        >
                                            <div className="summary-title">
                                                Error
                                            </div>
                                            <ul>
                                                <li>{decisionError}</li>
                                            </ul>
                                        </div>
                                    )}

                                    {decisionMessage && (
                                        <div
                                            className="summary"
                                            style={{ marginTop: 8 }}
                                        >
                                            <div className="summary-title">
                                                Decision applied
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
