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
} from './api';
import { amountRangeProblem } from '@shared/alertFilters';
import { formatMoney, parseAmount, readerLocale } from './money';
import { describeApiErrorLines } from '@shared/apiErrors';
import {
    alertStateLabel,
    authMethodLabel,
    describeDeclineReason,
    transferStatusLabel,
} from '@shared/glossary';
import {
    EMPTY_VALUE,
    formatAlertId,
    formatDateTime,
    formatIban,
    formatTransferId,
    NOT_RECORDED,
} from '@shared/format';
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
 * What actually happened, read off the alert the server sent back rather than off the button
 * that was pressed. Deriving it from the button was safe only while every decision did the one
 * thing its label said: a DECLINE on a payment that had already gone is now accepted and
 * records the verdict without stopping anything, and announcing "Transfer declined" for it
 * would tell the analyst the money was held when it is gone - a worse lie than the 409 it
 * replaced.
 */
function describeDecision(kind: FraudDecision, updated: AlertDetail): string {
    const status = updated.transfer.status;
    // Whatever the sentence says about the payment's state, it says in the same words the queue
    // and the panel above it use. It used to interpolate the enum: "the transfer is WAITING_AUTH".
    const state = transferStatusLabel(status, 'analyst').toLowerCase();

    if (kind === 'APPROVE') {
        return status === 'WAITING_AUTH'
            ? 'Alert cleared. The payment is released to the customer to confirm; no money has moved.'
            : `Alert cleared. The transfer was already ${state}, so there was nothing to release.`;
    }

    if (kind === 'DECLINE') {
        return status === 'SENT'
            ? 'Recorded as confirmed fraud. The payment had already been sent and has NOT been reversed.'
            : `Alert recorded as confirmed fraud, and the transfer is ${state}.`;
    }

    return 'Notes, assignee and tags saved. No decision was taken: the alert is still open and the transfer is unchanged.';
}

export default function FraudDeskPage({ role, brand, identity, onNavigate }: Props) {
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
            setCounters(null);
            setListError([problem]);
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
            setListError(describeApiErrorLines(e, 'alert-queue'));
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
            setDecisionMessage(describeDecision(kind, updated));

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
                              before applying any filter: New 7 over a list of three reads as a
                              contradiction until the line says which number is which. Counting
                              the visible list instead would be worse, since this page opens
                              filtered to New and two of the three would be permanently zero.
                              The three states are the only three, so their sum is the queue.

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
                                    Showing {alerts.length} with the filters below.
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
                                            <th className="cell--amount">Amount</th>
                                            <th>Reason</th>
                                            <th>Risk score</th>
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
                                                <td>{alertStateLabel(a.state)}</td>
                                                <td>
                                                    {transferStatusLabel(
                                                        a.transferStatus,
                                                        'analyst',
                                                    )}
                                                </td>
                                                <td className="cell--amount">
                                                    {formatMoney(a.amount)}
                                                </td>
                                                <td>{a.shortReason}</td>
                                                <td>
                                                    {a.riskScore ?? EMPTY_VALUE}
                                                </td>
                                                <td>
                                                    {a.assignee || EMPTY_VALUE}
                                                </td>
                                                <td>
                                                    {formatDateTime(a.createdAt)}
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
                                                {alertStateLabel(detail.alert.state)})
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
                                            <span className="fact-value">
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
                                        <p>
                                            <span className="fact-label">To:</span>{' '}
                                            <span className="fact-value">
                                                {formatIban(detail.transfer.toIban)}
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
                                                        <th className="cell--amount">
                                                            Amount
                                                        </th>
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
                                                                    {formatTransferId(h.id)}
                                                                </td>
                                                                <td>
                                                                    {formatDateTime(
                                                                        h.createdAt,
                                                                    )}
                                                                </td>
                                                                <td className="cell--amount">
                                                                    {formatMoney(
                                                                        h.amount,
                                                                    )}
                                                                </td>
                                                                <td>
                                                                    {transferStatusLabel(
                                                                        h.status,
                                                                        'analyst',
                                                                    )}
                                                                </td>
                                                                <td>
                                                                    {formatIban(h.toIban)}
                                                                </td>
                                                                {/* The same sentence the
                                                                    customer is now shown for
                                                                    their own declined payment,
                                                                    from the same function. */}
                                                                <td>
                                                                    {describeDeclineReason(
                                                                        h.declineReason,
                                                                    ) || EMPTY_VALUE}
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
                                                    : 'Approve: release to the customer'}
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
                                                Decline: record confirmed fraud
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
