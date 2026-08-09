// src/WaitingAuthorizationsPage.tsx
import { useEffect, useState } from 'react';
import './App.css';
import {
    fetchWaitingTransfers,
    fetchTransferDetails,
    confirmAuthorization,
    type ApiError,
    type WaitingTransferItem,
    type TransferDetails,
    type AuthorizePaymentResult,
    cancelTransfer,
    isUnderReview,
} from './api';
import { formatMoney } from './money';

/**
 * The one sentence a customer whose payment is held needs, kept identical to the server's
 * TRANSFER_UNDER_REVIEW message. It is rendered under the disabled Confirm button rather than
 * only as an error, because the button they would have to press to see the error is the one
 * that is disabled - so as an error alone it would be copy nobody ever reads.
 */
const UNDER_REVIEW_TEXT =
    'The bank is reviewing this payment. You will be able to confirm it once the review is ' +
    'finished, or you can cancel it below.';

interface Props {
    onNavigate: (view: 'new-payment' | 'waiting-auth' | 'fraud-desk') => void;
}

function mapDeclineReason(reason: string): string {
    const r = reason.toLowerCase();

    if (r.includes('otp failed') || r.includes('wrong otp')) {
        return 'Wrong one-time password (OTP). Please check the code and try again.';
    }

    if (r.includes('too many') || r.includes('attempts exceeded')) {
        return 'Too many incorrect OTP attempts - this transfer was declined for security reasons.';
    }

    if (r.includes('expired') || r.includes('authorization window')) {
        return 'Authorization time window has expired. Please create a new transfer if you still want to send money.';
    }

    if (r.includes('insufficient funds')) {
        return 'Insufficient balance - top up your account or cancel this transfer.';
    }

    if (r.includes('canceled by customer') || r.includes('cancelled by customer') || r.includes('canceled')) {
        return 'The transfer was canceled by the customer.';
    }

    return reason;
}

/**
 * Turns the server's error codes into one sentence for the customer. Every code below is
 * reachable on POST /api/transfers/{id}/authorize; there is no matching on message text,
 * which used to misfire whenever an unrelated message happened to contain "expired".
 *
 * The branches this replaces named WRONG_OTP, OTP_ATTEMPTS_EXCEEDED and OTP_EXPIRED -
 * three codes the backend has never sent, and none of them spelled the way INVALID_OTP is.
 */
function describeAuthorizationError(err: ApiError, triesLeft?: number): string {
    switch (err.code) {
        case 'INVALID_OTP': {
            const extra =
                triesLeft !== undefined && triesLeft !== null
                    ? ` You have ${triesLeft} attempt${triesLeft === 1 ? '' : 's'} left.`
                    : '';
            return 'Wrong one-time password (OTP). Please check the code and try again.' + extra;
        }
        case 'INSUFFICIENT_FUNDS':
            return 'Insufficient balance – top up your account and try again or cancel this transfer.';
        // Reachable for an API caller and for a customer whose payment was held between the
        // page loading and their pressing Confirm. Without this case it falls to `default`,
        // which renders the catalogue sentence but also leaves CONFLICT's "refresh the list"
        // advice as the nearest thing on screen, and refreshing shows nothing new.
        case 'TRANSFER_UNDER_REVIEW':
            return UNDER_REVIEW_TEXT;
        // A refused write, and it must sit above CONFLICT rather than fall through to `default`. The
        // catalogue sentence would render either way, but CONFLICT's "refresh the list" advice
        // is the nearest thing on screen and refreshing shows the transfer still waiting -
        // whereas the right action here is simply to confirm again.
        case 'CONCURRENT_MODIFICATION':
            return 'Another change was applied to this account first. Nothing was charged - please confirm again.';
        case 'CONFLICT':
            return 'This transfer can no longer be confirmed. Refresh the list to see its current state.';
        case 'NOT_FOUND':
            return 'This transfer is no longer available.';
        case 'VALIDATION_ERROR':
            return 'Please enter the one-time password before confirming.';
        default:
            return err.message || 'Authorization failed.';
    }
}

export function WaitingAuthorizationsPage({ onNavigate }: Props) {
    const [items, setItems] = useState<WaitingTransferItem[]>([]);
    const [selectedId, setSelectedId] = useState<number | null>(null);
    const [details, setDetails] = useState<TransferDetails | null>(null);
    const [otp, setOtp] = useState('');
    const [result, setResult] = useState<AuthorizePaymentResult | null>(null);

    // Separate errors for list/details and for authorization confirmation
    const [listError, setListError] = useState<string | null>(null);
    const [confirmError, setConfirmError] = useState<string | null>(null);

    const [loading, setLoading] = useState(false);
    const [refreshing, setRefreshing] = useState(false);

    useEffect(() => {
        void loadList();
    }, []);

    // The selected row as the list last reported it. The whole point of the status is that the
    // customer can see why Confirm is dead, so it has to come from the row rather than from
    // `details`, which may not have loaded yet.
    const selectedItem = items.find((x) => x.id === selectedId) ?? null;
    const selectedUnderReview =
        isUnderReview(selectedItem?.status as string | undefined) ||
        isUnderReview(details?.status);

    /**
     * Manual refresh. A held payment is released by somebody else, at a time the customer is
     * not told about, and nothing on this page polls - loadList runs on mount and after the
     * customer's own confirm or cancel. Without a control here the only way to notice a release
     * is to reload the browser tab, because clicking the already-active nav link does not
     * remount the page.
     */
    async function handleRefresh() {
        try {
            setRefreshing(true);
            await loadList();
            if (selectedId) {
                try {
                    setDetails(await fetchTransferDetails(selectedId));
                } catch {
                    // The transfer may no longer be readable; the list is the source of truth.
                }
            }
        } finally {
            setRefreshing(false);
        }
    }

    async function loadList() {
        try {
            // Errors from the list should not overwrite errors from confirm step
            setListError(null);
            const data = await fetchWaitingTransfers();
            setItems(data);
            // If selected transfer disappeared from the list, clear selection and details
            if (selectedId && !data.some((x) => x.id === selectedId)) {
                setSelectedId(null);
                setDetails(null);
            }
        } catch (e) {
            setListError((e as Error).message);
        }
    }

    async function handleSelect(id: number) {
        setSelectedId(id);
        setResult(null);
        try {
            setListError(null);
            const d = await fetchTransferDetails(id);
            setDetails(d);
        } catch (e) {
            setListError((e as Error).message);
        }
    }

    async function handleConfirm() {
        if (!selectedId || !otp) return;

        try {
            setLoading(true);
            setConfirmError(null);

            // Step 1: try to authorize the transfer with given OTP
            const res = await confirmAuthorization({ transferId: selectedId, otp });
            setResult(res);

            // Step 2: refresh the list (transfers that are no longer WAITING_AUTH will disappear)
            await loadList();

            // Step 3: reload details to reflect updated triesLeft / authValidUntil
            try {
                const d = await fetchTransferDetails(selectedId);
                setDetails(d);
            } catch {
                // If details are no longer available, ignore
            }

            // Step 4: interpret result and set a human-readable message.
            // A wrong code no longer arrives here: it is a 400 and lands in the catch below,
            // so WAITING_AUTH is not a possible outcome of a 200 any more. The third failed
            // attempt and an expired window still arrive as 200 DECLINED, which is why that
            // branch stays.
            if (res.status === 'DECLINED') {
                setConfirmError(
                    res.declineReason
                        ? mapDeclineReason(res.declineReason)
                        : 'Authorization was declined.',
                );
            } else {
                setConfirmError(null);
            }
        } catch (e) {
            const err = e as ApiError;

            // A refused authorization still changed the transfer: a wrong code costs one of
            // the three attempts. Refresh before reporting, so the "Tries left" readout and
            // the count inside the message are the ones the server now holds. Without this,
            // moving the wrong OTP off the 200 path would freeze the counter at its
            // pre-attempt value, because the refresh above is skipped on the throw.
            await loadList();
            let triesLeft: number | undefined;
            try {
                const d = await fetchTransferDetails(selectedId);
                setDetails(d);
                triesLeft = d.triesLeft;
            } catch {
                // Details may no longer be readable; the message does not depend on them.
            }

            setConfirmError(describeAuthorizationError(err, triesLeft));
        } finally {
            // Cleared on every outcome, including the refusal. It used to sit in the try,
            // so once a wrong code throws, the known-bad code would stay in the box with
            // Confirm still enabled - two impatient clicks away from burning the transfer.
            setOtp('');
            setLoading(false);
        }
    }

    async function handleCancel() {
        if (!selectedId) return;

        try {
            setLoading(true);
            setConfirmError(null);

            const res = await cancelTransfer(selectedId);
            setResult(res);

            // After cancellation the transfer will disappear from WAITING_AUTH list
            await loadList();

            // Details are less important after cancel, but we can try to refresh them
            try {
                const d = await fetchTransferDetails(selectedId);
                setDetails(d);
            } catch {
                setDetails(null);
            }
        } catch (e) {
            const err = e as ApiError;
            setConfirmError(
                err.code === 'CONFLICT'
                    ? 'This transfer can no longer be canceled – it has already been sent.'
                    : err.code === 'NOT_FOUND'
                        ? 'This transfer is no longer available.'
                        : err.message || 'Failed to cancel transfer.',
            );
        } finally {
            setLoading(false);
        }
    }

    return (
        <div className="app-shell">
            <div className="card">
                <header className="card-header">
                    <h1>Authorize Payment</h1>
                </header>

                <div className="card-body layout">
                    {/* Left: navigation for customer views */}
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
                                    Accounts
                                </button>
                            </li>
                            <li>
                                <button
                                    type="button"
                                    className="nav-link"
                                    onClick={() => onNavigate('new-payment')}
                                >
                                    New payment
                                </button>
                            </li>
                            <li>
                                <button type="button" className="nav-link">
                                    History & Statements
                                </button>
                            </li>
                            <li>
                                <button
                                    type="button"
                                    className="nav-link nav-link--active"
                                    onClick={() => onNavigate('waiting-auth')}
                                >
                                    Waiting authorizations
                                </button>
                            </li>
                            <li>
                                <button type="button" className="nav-link">
                                    Settings
                                </button>
                            </li>
                        </ul>
                    </nav>

                    {/* Right: waiting transfers table, details and authorization controls */}
                    <main className="form-panel">
                        {/* Section: list of waiting transfers */}
                        <section className="section">
                            <h2 className="section-title">Waiting transfers</h2>

                            <div className="section-block inline">
                                <button
                                    type="button"
                                    className="btn-secondary"
                                    onClick={handleRefresh}
                                    disabled={refreshing}
                                >
                                    {refreshing ? 'Refreshing…' : 'Refresh'}
                                </button>
                            </div>

                            {/* Errors related to list/details loading */}
                            {listError && (
                                <div
                                    className="summary"
                                    style={{ borderColor: 'salmon', marginBottom: 8 }}
                                >
                                    <div className="summary-title">Error</div>
                                    <ul>
                                        <li>{listError}</li>
                                    </ul>
                                </div>
                            )}

                            {items.length === 0 ? (
                                <p className="helper-text">No waiting transfers.</p>
                            ) : (
                                <div className="table-wrapper">
                                    <table className="table">
                                        <thead>
                                        <tr>
                                            <th>ID</th>
                                            <th>Beneficiary IBAN</th>
                                            <th>Amount</th>
                                            <th>Created</th>
                                            <th>Auth</th>
                                            <th>Status</th>
                                        </tr>
                                        </thead>
                                        <tbody>
                                        {items.map((it) => (
                                            <tr
                                                key={it.id}
                                                onClick={() => handleSelect(it.id)}
                                                className={
                                                    selectedId === it.id ? 'table-row--selected' : ''
                                                }
                                            >
                                                <td>{it.id}</td>
                                                <td>{it.targetIban || (it as any).beneficiaryIban}</td>
                                                <td>{formatMoney(it.amount)}</td>
                                                <td>
                                                    {it.createdAt
                                                        ? new Date(it.createdAt).toLocaleString()
                                                        : ''}
                                                </td>
                                                <td>{it.authMethod}</td>
                                                <td>
                                                    {isUnderReview(
                                                        it.status as string | undefined,
                                                    )
                                                        ? 'Under review'
                                                        : 'Waiting for your code'}
                                                </td>
                                            </tr>
                                        ))}
                                        </tbody>
                                    </table>
                                </div>
                            )}
                        </section>

                        {/* Section: details of selected transfer */}
                        <section className="section">
                            <h2 className="section-title">Selected transfer details</h2>
                            <div className="section-block">
                                {details ? (
                                    <div>
                                        <p>
                                            <strong>From:</strong> {details.fromIban}{' '}
                                            {details.fromBalance &&
                                                `(Balance: ${formatMoney(details.fromBalance)})`}
                                        </p>
                                        <p>
                                            <strong>To:</strong> {details.toIban}</p>
                                        <p>
                                            <strong>Amount:</strong> {formatMoney(details.amount)}
                                        </p>
                                        <p>
                                            <strong>Fee:</strong> {formatMoney(details.feeAmount)}</p>
                                        <p>
                                            <strong>Created:</strong>{' '}
                                            {details.createdAt
                                                ? new Date(details.createdAt).toLocaleString()
                                                : ''}
                                        </p>
                                        <p>
                                            <strong>Status:</strong> {details.status}</p>
                                        <p>
                                            <strong>Auth method:</strong> {details.authMethod || '—'}
                                        </p>
                                    </div>
                                ) : (
                                    <p className="helper-text">No transfer selected.</p>
                                )}
                            </div>
                        </section>

                        {/* Section: OTP confirmation + result */}
                        <section className="section">
                            <h2 className="section-title">Confirm authorization</h2>
                            <div className="section-block inline">
                                <input
                                    className="otp-input"
                                    type="text"
                                    value={otp}
                                    onChange={(e) => setOtp(e.target.value)}
                                    placeholder="Enter OTP"
                                    maxLength={10}
                                    disabled={selectedUnderReview}
                                />
                                <button
                                    type="button"
                                    className="btn-primary"
                                    onClick={handleConfirm}
                                    disabled={
                                        !selectedId || !otp || loading || selectedUnderReview
                                    }
                                >
                                    {loading ? 'Confirming…' : 'Confirm'}
                                </button>

                                {/* Never disabled by the review: a held payment has no expiry
                                    of its own, so this is the customer's only way out of the
                                    queue if nobody works it. */}
                                <button
                                    type="button"
                                    className="btn-secondary"
                                    onClick={handleCancel}
                                    disabled={!selectedId || loading}
                                    style={{ marginLeft: 8 }}
                                >
                                    Cancel transfer
                                </button>
                            </div>

                            {selectedUnderReview && (
                                <p className="helper-text">{UNDER_REVIEW_TEXT}</p>
                            )}

                            <div className="helper-text">
                                <p>
                                    <strong>Tries left:</strong>{' '}
                                    {details?.triesLeft ?? '—'}
                                </p>
                                <p>
                                    <strong>Will be expired after:</strong>{' '}
                                    {details?.authValidUntil
                                        ? new Date(details.authValidUntil).toLocaleString()
                                        : '—'}
                                </p>
                            </div>

                            {/* Errors related to authorization confirmation */}
                            {confirmError && (
                                <div
                                    className="summary"
                                    style={{ borderColor: 'salmon', marginTop: 8 }}
                                >
                                    <div className="summary-title">Error</div>
                                    <ul>
                                        <li>{confirmError}</li>
                                    </ul>
                                </div>
                            )}

                            {result && (
                                <div className="summary" style={{ marginTop: 10 }}>
                                    <div className="summary-title">
                                        {result.status === 'SENT'
                                            ? 'Payment authorized'
                                            : 'Authorization result'}
                                    </div>
                                    <ul>
                                        <li>Transfer ID: {result.transferId}</li>
                                        <li>Status: {result.status}</li>

                                        {/* Show charged amount only if funds were actually debited */}
                                        {result.chargedAmount && (
                                            <li>Charged: {formatMoney(result.chargedAmount)}</li>
                                        )}

                                        {/* newBalance is always current; wording changes depending on status */}
                                        <li>
                                            {result.status === 'SENT' ? 'New balance: ' : 'Current balance: '}
                                            {formatMoney(result.newBalance)}
                                        </li>

                                        {/* Decline reason, if present */}
                                        {result.declineReason && (
                                            <li>Reason: {mapDeclineReason(result.declineReason)}</li>
                                        )}
                                    </ul>
                                </div>
                            )}

                        </section>
                    </main>
                </div>
            </div>
        </div>
    );
}
