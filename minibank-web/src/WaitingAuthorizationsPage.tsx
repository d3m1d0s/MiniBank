// src/WaitingAuthorizationsPage.tsx
import { useEffect, useState, type ReactNode } from 'react';
import './App.css';
import {
    fetchWaitingTransfers,
    fetchTransferDetails,
    confirmAuthorization,
    type Page,
    type WaitingTransferItem,
    type TransferDetails,
    type AuthorizePaymentResult,
    cancelTransfer,
    isUnderReview,
} from './api';
import { formatMoney } from './money';
import { describeApiErrorLines } from '@shared/apiErrors';
import {
    authMethodLabel,
    describeDeclineReason,
    transferStatusLabel,
    transferStatusTone,
} from '@shared/glossary';
import { formatDateTime, formatIban, formatTransferId, NOT_RECORDED } from '@shared/format';
import {
    MAX_PAGE_SIZE,
    SHOW_MORE,
    SHOW_MORE_BUSY,
    appendPage,
    hasMore,
    nextPage,
    showingLine,
} from '@shared/paging';
import Nav from './Nav';
import type { NavRole, NavView } from '@shared/navigation';

/**
 * How many waiting payments arrive at a time.
 *
 * The server's own default, and larger than the customer history's five: a work list is meant to
 * be seen entire, and what a customer wants at its foot is to have finished reading rather than
 * to go on.
 */
const PAGE_SIZE = 25;

/**
 * The one sentence a customer whose payment is held needs. It is rendered under the disabled
 * Confirm button rather than only as an error, because the button they would have to press to
 * see the error is the one that is disabled - so as an error alone it would be copy nobody ever
 * reads.
 *
 * Word for word the TRANSFER_UNDER_REVIEW entry of the shared error table, which is where the
 * same sentence is written for the case where the server does refuse a confirmation. It cannot
 * be read from there without inventing a failure that did not happen, so it is kept here and
 * kept identical, and it names no position on the screen for the reason that table gives.
 */
const UNDER_REVIEW_TEXT =
    'The bank is reviewing this payment. You will be able to confirm it once the review is ' +
    'finished, or you can cancel it.';

interface Props {
    role: NavRole;
    /* The mark and the name of the application, built by App and rendered here as it arrives. */
    brand?: ReactNode;
    /* Who is signed in and the way out, built by App and rendered here as it arrives. */
    identity?: ReactNode;
    onNavigate: (view: NavView) => void;
}

export function WaitingAuthorizationsPage({ role, brand, identity, onNavigate }: Props) {
    const [items, setItems] = useState<WaitingTransferItem[]>([]);

    /**
     * The page envelope as it last arrived, so the next request is asked for by the page that
     * came back rather than by the number of rows on screen. Those two part company the moment a
     * row arrives twice and is dropped; see appendPage.
     */
    const [last, setLast] = useState<Page<WaitingTransferItem> | null>(null);
    const [selectedId, setSelectedId] = useState<number | null>(null);
    const [details, setDetails] = useState<TransferDetails | null>(null);
    const [otp, setOtp] = useState('');
    const [result, setResult] = useState<AuthorizePaymentResult | null>(null);

    // Separate errors for list/details and for authorization confirmation. Sentences rather than
    // one string: the shared table answers with a statement of what happened and, where there is
    // one, the action to take, and the box below renders them as the separate lines they are.
    const [listError, setListError] = useState<string[] | null>(null);
    const [confirmError, setConfirmError] = useState<string[] | null>(null);

    const [loading, setLoading] = useState(false);
    const [refreshing, setRefreshing] = useState(false);

    /**
     * Whether the first list request has finished, and it starts as not.
     *
     * Without it the screen drew an in-flight list as an empty one: it said "No waiting
     * transfers." over an API answering with two, for as long as the request took. The empty
     * sentence may only be reached once a request has completed.
     */
    const [loadingList, setLoadingList] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);

    useEffect(() => {
        void loadList();
    }, []);

    // The selected row as the list last reported it. The whole point of the status is that the
    // customer can see why Confirm is dead, so it has to come from the row rather than from
    // `details`, which may not have loaded yet.
    const selectedItem = items.find((x) => x.id === selectedId) ?? null;
    const selectedUnderReview =
        isUnderReview(selectedItem?.status) || isUnderReview(details?.status);

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

    /**
     * Reads the list from the top, keeping as many rows as are already on screen.
     *
     * Called on mount and after every confirm or cancel, so it must not quietly collapse a list
     * somebody has opened out: it asks for one page as large as what is held rather than for the
     * first page of twenty-five, which is one statement either way. The ceiling is the server's,
     * and a customer with more than a hundred payments waiting on their code is not a case this
     * screen has.
     */
    async function loadList() {
        try {
            // Errors from the list should not overwrite errors from confirm step
            setListError(null);
            const wanted = Math.min(MAX_PAGE_SIZE, Math.max(PAGE_SIZE, items.length));
            const answer = await fetchWaitingTransfers(0, wanted);
            setItems(answer.items);
            setLast(answer);
            // If selected transfer disappeared from the list, clear selection and details
            if (selectedId && !answer.items.some((x) => x.id === selectedId)) {
                setSelectedId(null);
                setDetails(null);
            }
        } catch (e) {
            setListError(describeApiErrorLines(e, 'payments-waiting'));
        } finally {
            setLoadingList(false);
        }
    }

    /**
     * The next page, appended under the rows already on screen.
     *
     * A failure here leaves those rows where they are and returns the button to its resting
     * label, so it can be pressed again; the sentence goes in the list's own error box.
     */
    async function loadMore() {
        if (!last) return;

        try {
            setLoadingMore(true);
            setListError(null);
            const answer = await fetchWaitingTransfers(nextPage(last), PAGE_SIZE);
            setItems((held) => appendPage(held, answer.items));
            setLast(answer);
        } catch (e) {
            setListError(describeApiErrorLines(e, 'payments-waiting'));
        } finally {
            setLoadingMore(false);
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
            setListError(describeApiErrorLines(e, 'payment-details'));
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
                setConfirmError([
                    res.declineReason
                        ? describeDeclineReason(res.declineReason)
                        : 'Authorization was declined.',
                ]);
            } else {
                setConfirmError(null);
            }
        } catch (e) {
            // A refused authorization still changed the transfer: a wrong code costs one of
            // the three attempts. Refresh before reporting, so the "Tries left" readout and
            // the count inside the message are the ones the server now holds. Without this,
            // moving the wrong OTP off the 200 path would freeze the counter at its
            // pre-attempt value, because the refresh above is skipped on the throw.
            await loadList();
            let triesLeft: number | null | undefined;
            try {
                const d = await fetchTransferDetails(selectedId);
                setDetails(d);
                triesLeft = d.triesLeft;
            } catch {
                // Details may no longer be readable; the message does not depend on them.
            }

            setConfirmError(describeApiErrorLines(e, 'payment-authorize', { triesLeft }));
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
            setConfirmError(describeApiErrorLines(e, 'payment-cancel'));
        } finally {
            setLoading(false);
        }
    }

    return (
        <div className="app-shell">
            <div className="card">
                <header className="card-header">
                    {brand}
                    {identity}
                </header>

                <div className="card-body layout">
                    <Nav role={role} current="waiting-auth" onNavigate={onNavigate} />

                    {/* Right: waiting transfers table, details and authorization controls */}
                    <main className="form-panel">
                        <h2>Authorize Payment</h2>

                        {/* Section: list of waiting transfers */}
                        <section className="section">
                            <h2 className="section-title">Waiting transfers</h2>

                            {/* Refreshing decides nothing, so it carries no shape of its own.
                                With no row selected it used to be the loudest control here. */}
                            <div className="section-block inline">
                                <button
                                    type="button"
                                    className="btn-quiet"
                                    onClick={handleRefresh}
                                    disabled={refreshing}
                                >
                                    {refreshing ? 'Refreshing…' : 'Refresh'}
                                </button>
                            </div>

                            {/* Errors related to list/details loading */}
                            {listError && (
                                <div className="summary summary--danger gap-below-sm">
                                    <div className="summary-title">Error</div>
                                    <ul>
                                        {listError.map((line) => (
                                            <li key={line}>{line}</li>
                                        ))}
                                    </ul>
                                </div>
                            )}

                            {loadingList ? (
                                <p className="helper-text">Loading waiting transfers…</p>
                            ) : items.length === 0 ? (
                                <p className="helper-text">No waiting transfers.</p>
                            ) : (
                                <>
                                <div className="table-wrapper">
                                    <table className="table">
                                        <thead>
                                        <tr>
                                            <th>ID</th>
                                            <th>Beneficiary IBAN</th>
                                            <th className="cell--amount">Amount</th>
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
                                                <td>{formatTransferId(it.id)}</td>
                                                <td>{formatIban(it.toIban)}</td>
                                                <td className="cell--amount">
                                                    {formatMoney(it.amount)}
                                                </td>
                                                <td>{formatDateTime(it.createdAt)}</td>
                                                <td>{authMethodLabel(it.authMethod)}</td>
                                                {/* The two sentences this column used to write
                                                    itself are the glossary's now, and they are
                                                    the wording it was built out from. The
                                                    treatment comes with them: a payment the bank
                                                    is holding and one waiting for a code are two
                                                    different things to a customer looking for
                                                    what to do next. */}
                                                <td>
                                                    <span
                                                        className={`tone-${transferStatusTone(
                                                            it.status,
                                                        )}`}
                                                    >
                                                        {transferStatusLabel(
                                                            it.status,
                                                            'customer',
                                                        )}
                                                    </span>
                                                </td>
                                            </tr>
                                        ))}
                                        </tbody>
                                    </table>
                                </div>

                                {/*
                                  Paging decides nothing, so the control carries no shape of its
                                  own, the same rank as Refresh above it. Removed rather than
                                  disabled once the whole list is on screen: a dead control still
                                  invites the press that proves it.
                                */}
                                <div className="section-block inline gap-above-sm">
                                    {hasMore(items.length, last?.total ?? 0) && (
                                        <button
                                            type="button"
                                            className="btn-quiet"
                                            onClick={() => void loadMore()}
                                            disabled={loadingMore}
                                            aria-busy={loadingMore || undefined}
                                        >
                                            {loadingMore ? SHOW_MORE_BUSY : SHOW_MORE}
                                        </button>
                                    )}
                                    <span className="list-count">
                                        {showingLine(items.length, last?.total ?? 0)}
                                    </span>
                                </div>
                                </>
                            )}
                        </section>

                        {/* Section: details of selected transfer */}
                        <section className="section">
                            <h2 className="section-title">Selected transfer details</h2>
                            <div className="section-block">
                                {details ? (
                                    /*
                                     * A label names its fact, it does not outweigh it. These were
                                     * <strong>, which is 700, under a section heading at 600: the
                                     * word "Amount:" was heavier than the amount. The label steps
                                     * down in colour instead, and the one figure the screen is
                                     * about takes the lead rung.
                                     */
                                    <div>
                                        <p>
                                            <span className="fact-label">From:</span>{' '}
                                            <span className="fact-value">
                                                {formatIban(details.fromIban)}
                                            </span>{' '}
                                            {details.fromBalance && (
                                                <span className="fact-value">
                                                    (Balance: {formatMoney(details.fromBalance)})
                                                </span>
                                            )}
                                        </p>
                                        <p>
                                            <span className="fact-label">To:</span>{' '}
                                            <span className="fact-value">
                                                {formatIban(details.toIban)}
                                            </span>
                                        </p>
                                        <p>
                                            <span className="fact-label">Amount:</span>{' '}
                                            <span className="fact-value fact-value--lead">
                                                {formatMoney(details.amount)}
                                            </span>
                                        </p>
                                        <p>
                                            <span className="fact-label">Fee:</span>{' '}
                                            <span className="fact-value">
                                                {formatMoney(details.feeAmount)}
                                            </span>
                                        </p>
                                        <p>
                                            <span className="fact-label">Created:</span>{' '}
                                            <span className="fact-value">
                                                {formatDateTime(details.createdAt)}
                                            </span>
                                        </p>
                                        <p>
                                            <span className="fact-label">Status:</span>{' '}
                                            <span
                                                className={`fact-value tone-${transferStatusTone(
                                                    details.status,
                                                )}`}
                                            >
                                                {transferStatusLabel(details.status, 'customer')}
                                            </span>
                                        </p>
                                        <p>
                                            <span className="fact-label">Auth method:</span>{' '}
                                            <span className="fact-value">
                                                {authMethodLabel(details.authMethod) ||
                                                    NOT_RECORDED}
                                            </span>
                                        </p>
                                        {/*
                                          Why the payment was stopped, in the customer's own
                                          words for it. The analyst has been able to read this
                                          string in the alert history of this very transfer all
                                          along; its owner could not read it anywhere. It is
                                          reached from this screen by cancelling, and by a third
                                          wrong code, both of which leave the transfer selected.
                                        */}
                                        {details.declineReason && (
                                            <p>
                                                <span className="fact-label">
                                                    Why it was stopped:
                                                </span>{' '}
                                                <span className="fact-value">
                                                    {describeDeclineReason(details.declineReason)}
                                                </span>
                                            </p>
                                        )}
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
                                    value={selectedUnderReview ? '' : otp}
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
                                    queue if nobody works it.

                                    Destructive, and drawn as one: an edge and a label, never a
                                    fill. It is also pushed to the far end of the row, so the gap
                                    itself says it is not one of the pair that finishes the
                                    payment - and that is where the confirmation step will
                                    attach. */}
                                <button
                                    type="button"
                                    className="btn-secondary btn-secondary--danger push-end"
                                    onClick={handleCancel}
                                    disabled={!selectedId || loading}
                                >
                                    Cancel transfer
                                </button>
                            </div>

                            {selectedUnderReview && (
                                <p className="helper-text">{UNDER_REVIEW_TEXT}</p>
                            )}

                            {/*
                              Both facts belong to a transfer that is asking for a code, and the
                              server now sends them as null on one that is not. Drawn only when
                              there is something to say: a held payment used to report three
                              attempts beside a Confirm button it will not take, and after the
                              third wrong code the pair would have read "0" and a deadline for a
                              transfer that is already declined.
                            */}
                            {(details?.triesLeft != null || details?.authValidUntil != null) && (
                                <div className="helper-text">
                                    <p>
                                        <span className="fact-label">Tries left:</span>{' '}
                                        <span className="fact-value">{details.triesLeft}</span>
                                    </p>
                                    <p>
                                        <span className="fact-label">
                                            Will be expired after:
                                        </span>{' '}
                                        <span className="fact-value">
                                            {formatDateTime(details.authValidUntil)}
                                        </span>
                                    </p>
                                </div>
                            )}

                            {/* Errors related to authorization confirmation */}
                            {confirmError && (
                                <div className="summary summary--danger gap-above-sm">
                                    <div className="summary-title">Error</div>
                                    <ul>
                                        {confirmError.map((line) => (
                                            <li key={line}>{line}</li>
                                        ))}
                                    </ul>
                                </div>
                            )}

                            {result && (
                                <div className="summary gap-above-md">
                                    <div className="summary-title">
                                        {result.status === 'SENT'
                                            ? 'Payment authorized'
                                            : 'Authorization result'}
                                    </div>
                                    <ul>
                                        <li>Transfer: {formatTransferId(result.transferId)}</li>
                                        <li>
                                            Status:{' '}
                                            <span
                                                className={`tone-${transferStatusTone(
                                                    result.status,
                                                )}`}
                                            >
                                                {transferStatusLabel(result.status, 'customer')}
                                            </span>
                                        </li>

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
                                            <li>
                                                Reason:{' '}
                                                {describeDeclineReason(result.declineReason)}
                                            </li>
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
