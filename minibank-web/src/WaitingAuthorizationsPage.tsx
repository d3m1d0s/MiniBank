// src/WaitingAuthorizationsPage.tsx
import { useEffect, useRef, useState, type ReactNode } from 'react';
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
import ErrorBox from './ErrorBox';
import { describeApiFailure, type ApiFailure } from '@shared/apiErrors';
import {
    REFRESH,
    REFRESH_BUSY,
    authMethodLabel,
    bankBoundaryLabel,
    describeDeclineReason,
    dispatchStateLabel,
    transferStatusLabel,
    transferStatusTone,
} from '@shared/glossary';
import { formatDateTime, formatIban, formatTransferId, NOT_RECORDED } from '@shared/format';
import {
    TRANSFER_DETAIL_FIELDS,
    TRANSFER_DETAIL_LABEL,
    type TransferDetailCells,
    type TransferDetailField,
} from '@shared/fields';
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

/**
 * The one fact this panel names differently from the shared map, and it is a difference of
 * audience rather than a second word for a field.
 *
 * The tables call it the decline reason, which is what an analyst filtering payments is looking
 * for. The person in front of this screen is the customer whose payment it was, and what they are
 * reading is one sentence about one payment of their own. The same split transferStatusLabel makes
 * by audience; fields.ts names it where the field is declared.
 */
const CUSTOMER_DETAIL_LABEL: Partial<Record<TransferDetailField, string>> = {
    declineReason: 'Why it was stopped',
};

/** The one figure this screen is about takes the lead rung, as it did before the panel was a list. */
const DETAIL_VALUE_CLASS: Partial<Record<TransferDetailField, string>> = {
    amount: 'fact-value--lead',
};

/**
 * One payment read in full, a value per fact.
 *
 * Built through the shared row type, so a fact the server sends and this panel forgets is a build
 * failure rather than something a customer discovers is missing. Three of them were exactly that
 * until now: the reference the customer typed into the form, counted against 140 characters and
 * stored, and never once shown back; the moment the money actually moved; and what is left to
 * happen to a payment that has left the account.
 *
 * `null` is a fact with nothing to say and it is drawn as no line at all. That is a decision the
 * total cell type obliges this panel to take rather than an omission it can drift into: there is
 * no settlement instant on a payment that has not settled, no onward leg on one credited inside
 * this bank, and no reference where the customer typed none. A dash in their place would read as a
 * value withheld, which is the reading NOT_RECORDED exists to avoid one line further down.
 */
function detailCells(d: TransferDetails): TransferDetailCells<ReactNode | null> {
    return {
        fromIban: formatIban(d.fromIban),
        fromBalance: formatMoney(d.fromBalance),
        // Where the account is held, beside the account it is about. A payment that has left the
        // bank cannot be pulled back, and this panel stands over the Cancel button, so the fact
        // belongs on the screen where that choice is made. Both readings are printed: a panel
        // listing facts one to a line is not scanned the way a column is.
        toIban: `${formatIban(d.toIban)} (${bankBoundaryLabel(d.toIbanInBank)})`,
        amount: formatMoney(d.amount),
        fee: formatMoney(d.feeAmount),
        status: (
            <span className={`tone-${transferStatusTone(d.status)}`}>
                {transferStatusLabel(d.status, 'customer')}
            </span>
        ),
        createdAt: formatDateTime(d.createdAt),
        settledAt: d.settledAt ? formatDateTime(d.settledAt) : null,
        // The empty string is what the glossary answers for a null, and it is three different
        // situations rather than a state: money credited inside this bank, a payment that has not
        // settled, and every row written before the column existed. None of the three may be
        // drawn as "the money stayed here", which is what the To line above answers instead.
        dispatchState: dispatchStateLabel(d.dispatchState) || null,
        authMethod: authMethodLabel(d.authMethod) || NOT_RECORDED,
        message: d.message || null,
        // Why the payment was stopped, in the customer's own words for it. The analyst has been
        // able to read this string in the alert history of this very payment all along.
        declineReason: d.declineReason ? describeDeclineReason(d.declineReason) : null,
    };
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

    /**
     * Which payment is open, readable from inside a request that started before it.
     *
     * The state is not: a handler closes over the value it had when it ran, so an answer that
     * arrives after the customer has clicked another row has no way to tell that it is late. Every
     * read of the detail is checked against this before it reaches the screen, which is the whole
     * of the fix: clicking row 8 used to move the highlight and leave transfer 9's amount in the
     * panel for as long as the new request took, with Cancel live over it.
     */
    const selectedRef = useRef<number | null>(null);

    /*
     * Three failures, three places on the screen, because they are three different statements.
     * The list's own failure stands where the list would be; the detail's stands in the panel that
     * would have read the payment; a refused confirm or cancel stands under the buttons that were
     * pressed. They used to share one box above the list, so a payment that could not be read
     * reported itself as a broken list of payments.
     */
    const [listError, setListError] = useState<ApiFailure | null>(null);
    const [detailError, setDetailError] = useState<ApiFailure | null>(null);
    const [confirmError, setConfirmError] = useState<ApiFailure | null>(null);

    const [loading, setLoading] = useState(false);
    const [refreshing, setRefreshing] = useState(false);

    /**
     * Whether a first page of the list is in flight.
     *
     * Without it the screen drew an in-flight list as an empty one: it said "No waiting
     * transfers." over an API answering with two, for as long as the request took. It starts true
     * because the first request is started on mount, and the rows on screen outrank it in the
     * render below, so a reload after a confirm does not blank a table the customer is reading.
     */
    const [loadingList, setLoadingList] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);

    /**
     * Whether the selected payment is being read right now.
     *
     * Both fraud desks have had this since they were written and this screen had nothing: the row
     * lit up and the panel below went on showing the previous payment, or said "No transfer
     * selected." while the answer was on its way.
     */
    const [loadingDetail, setLoadingDetail] = useState(false);

    useEffect(() => {
        void loadList();
    }, []);

    // The selected row as the list last reported it. The whole point of the status is that the
    // customer can see why Confirm is dead, so it has to come from the row rather than from
    // `details`, which may not have loaded yet.
    const selectedItem = items.find((x) => x.id === selectedId) ?? null;
    const selectedUnderReview =
        isUnderReview(selectedItem?.status) || isUnderReview(details?.status);

    // Built once per render rather than once per field: the panel below walks the field list, and
    // calling the builder inside that walk would rebuild all twelve facts twelve times.
    const detailFacts = details ? detailCells(details) : null;

    /**
     * Whether the payment in the panel is the payment the buttons would act on.
     *
     * It is false while a detail is in flight and false after one has failed, and both of those
     * disable Confirm and Cancel. That is the invariant this screen lost: the actions were keyed
     * to `selectedId`, which is set the instant a row is clicked, so for the whole of the request
     * the customer could cancel a payment whose amount was not the one in front of them.
     */
    const detailReady = details !== null && !loadingDetail;

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
            // Refreshed together, because the panel is a reading of one row of the list it stands
            // under. The failure of this half used to be swallowed by an empty catch: the list
            // moved, the panel kept the payment it had, and Cancel stayed live over it. It is
            // reported in the panel now, like every other failed read of a detail.
            const open = selectedRef.current;
            if (open != null) {
                await loadDetails(open);
            }
        } finally {
            setRefreshing(false);
        }
    }

    /**
     * Reads one payment in full, and puts it on screen only if it is still the one selected.
     *
     * The single door to fetchTransferDetails, which had four call sites and a different amount of
     * care at each: one checked nothing, two swallowed the failure and one blanked the panel. The
     * id is checked before the request as well as after it, because loadList clears the selection
     * when the payment has left the list, and asking for a payment nobody is looking at is a
     * request that can only do harm.
     */
    async function loadDetails(id: number): Promise<TransferDetails | null> {
        if (selectedRef.current !== id) return null;

        try {
            setLoadingDetail(true);
            setDetailError(null);
            const d = await fetchTransferDetails(id);
            if (selectedRef.current !== id) return null;
            setDetails(d);
            return d;
        } catch (e) {
            if (selectedRef.current !== id) return null;
            // The payment goes with its failure. A panel that keeps the last readable version of
            // a payment while saying it could not be read is the state this whole change is about.
            setDetails(null);
            setDetailError(
                describeApiFailure(e, 'payment-details', { retry: () => void loadDetails(id) }),
            );
            return null;
        } finally {
            // Guarded like the rest: a late answer clearing this flag would take the loading state
            // off the request that replaced it.
            if (selectedRef.current === id) setLoadingDetail(false);
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
            setLoadingList(true);
            const wanted = Math.min(MAX_PAGE_SIZE, Math.max(PAGE_SIZE, items.length));
            const answer = await fetchWaitingTransfers(0, wanted);
            setItems(answer.items);
            setLast(answer);
            // If selected transfer disappeared from the list, clear selection and details.
            // The ref goes with the state, and it is what makes a detail request started before
            // this drop its answer instead of drawing a payment that is no longer selected.
            if (selectedId && !answer.items.some((x) => x.id === selectedId)) {
                selectedRef.current = null;
                setSelectedId(null);
                setDetails(null);
                setDetailError(null);
                setOtp('');
            }
        } catch (e) {
            setListError(
                describeApiFailure(e, 'payments-waiting', { retry: () => void loadList() }),
            );
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
            // The retry asks for the page that failed and not for the first one, which is the
            // difference between this box and the one that stands where the list would be.
            setListError(
                describeApiFailure(e, 'payments-waiting', { retry: () => void loadMore() }),
            );
        } finally {
            setLoadingMore(false);
        }
    }

    /**
     * Opens another payment, and clears everything on the screen that was about the last one.
     *
     * Everything below the selection belongs to the row that was selected: the payment in the
     * panel, the code half typed into the box, the refusal of the last confirm and the receipt of
     * the last one that worked. Carried across, each of them is a statement about one payment
     * standing under another, and the code is the dangerous one, since Confirm would send it.
     */
    async function handleSelect(id: number) {
        selectedRef.current = id;
        setSelectedId(id);
        setDetails(null);
        setDetailError(null);
        setResult(null);
        setConfirmError(null);
        setOtp('');
        await loadDetails(id);
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

            // Step 3: reload details to reflect updated triesLeft / authValidUntil. A payment that
            // has just been sent has left the list, so loadList above has cleared the selection
            // and this reads nothing: the receipt below is what says what happened to it.
            await loadDetails(selectedId);

            // Step 4: interpret result and set a human-readable message.
            // A wrong code no longer arrives here: it is a 400 and lands in the catch below,
            // so WAITING_AUTH is not a possible outcome of a 200 any more. The third failed
            // attempt and an expired window still arrive as 200 DECLINED, which is why that
            // branch stays.
            if (res.status === 'DECLINED') {
                // Not a failed call: the bank answered, and this is what it answered. There is no
                // status and no code to print under it, and nothing to try again.
                setConfirmError({
                    lines: [
                        res.declineReason
                            ? describeDeclineReason(res.declineReason)
                            : 'Authorization was declined.',
                    ],
                    reference: null,
                });
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
            const d = await loadDetails(selectedId);

            // No retry on this one, and none on the cancel below either. Confirming is not a read:
            // a second press is a second attempt against a counter of three, and the shared type
            // says as much where the control is declared.
            setConfirmError(
                describeApiFailure(e, 'payment-authorize', { triesLeft: d?.triesLeft }),
            );
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

            // After cancellation the transfer will disappear from WAITING_AUTH list, so this
            // reads nothing in the ordinary case: loadList has cleared the selection above and
            // the receipt below carries the outcome.
            await loadList();
            await loadDetails(selectedId);
        } catch (e) {
            setConfirmError(describeApiFailure(e, 'payment-cancel'));
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
                                With no row selected it used to be the loudest control here.

                                The two words come from the glossary now, and this screen is the
                                third that reads them. It said `Refreshing…` while the two fraud
                                desks said two other things for the same request; one wait has one
                                word, and it is the one the Show more control beside it says. */}
                            <div className="section-block inline">
                                <button
                                    type="button"
                                    className="btn-quiet"
                                    onClick={handleRefresh}
                                    disabled={refreshing}
                                    aria-busy={refreshing || undefined}
                                >
                                    {refreshing ? REFRESH_BUSY : REFRESH}
                                </button>
                            </div>

                            {/*
                              One statement where the list goes, and only one. The error box used
                              to stand above this chain rather than inside it, so a failed reload
                              printed a refusal over a table of live rows, and a failed detail
                              printed it over a list that was perfectly fine.

                              The order is the order of the reader's questions. Rows outrank
                              everything, because a reload in flight must not blank a table
                              somebody is reading; then whether a request is on its way; then, on
                              an empty screen, why. A page that failed while rows are already up is
                              a different statement and is drawn at the foot, beside the button
                              that asked for it.
                            */}
                            {items.length === 0 && loadingList ? (
                                <p className="helper-text">Loading waiting transfers…</p>
                            ) : items.length === 0 && listError ? (
                                <ErrorBox failure={listError} />
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

                                {/* The page that did not arrive, under the rows that did. It is
                                    about the request rather than about the list, which is why it
                                    stands here and not where the table is. */}
                                {listError && <ErrorBox failure={listError} />}
                                </>
                            )}
                        </section>

                        {/* Section: details of selected transfer */}
                        <section className="section">
                            <h2 className="section-title">Selected transfer details</h2>
                            <div className="section-block">
                                {/*
                                  The same rule as the list, in the same order: what is on its way,
                                  then why nothing came, then the payment, then the sentence for a
                                  screen where nothing is selected. The panel used to have two of
                                  these and no flag between them, so a click on another row left
                                  the previous payment standing for the length of the request.
                                */}
                                {loadingDetail ? (
                                    <p className="helper-text">Loading payment…</p>
                                ) : detailError ? (
                                    <ErrorBox failure={detailError} />
                                ) : details ? (
                                    /*
                                     * A label names its fact, it does not outweigh it. These were
                                     * <strong>, which is 700, under a section heading at 600: the
                                     * word "Amount:" was heavier than the amount. The label steps
                                     * down in colour instead, and the one figure the screen is
                                     * about takes the lead rung.
                                     *
                                     * The order is the shared field list's and not this file's:
                                     * where the money went, what it was, what happened to it, with
                                     * the two timestamps together because they are read against
                                     * each other. The workstation grows this same panel later and
                                     * has to lay the same facts out in the same order.
                                     */
                                    <div className="facts">
                                        {TRANSFER_DETAIL_FIELDS.map((f) => {
                                            const value = detailFacts?.[f] ?? null;
                                            if (value === null) return null;

                                            return (
                                                <p key={f}>
                                                    <span className="fact-label">
                                                        {CUSTOMER_DETAIL_LABEL[f] ??
                                                            TRANSFER_DETAIL_LABEL[f]}
                                                        :
                                                    </span>{' '}
                                                    <span
                                                        className={`fact-value ${
                                                            DETAIL_VALUE_CLASS[f] ?? ''
                                                        }`.trimEnd()}
                                                    >
                                                        {value}
                                                    </span>
                                                </p>
                                            );
                                        })}
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
                                {/*
                                  Every control in this row is dead until the payment it would act
                                  on is the payment on screen. It was keyed to the selection, which
                                  is set the instant a row is clicked: for the whole of the request
                                  after that the box took a code and Confirm would send it against
                                  a payment the customer had not read yet.
                                */}
                                <input
                                    className="otp-input"
                                    type="text"
                                    value={selectedUnderReview ? '' : otp}
                                    onChange={(e) => setOtp(e.target.value)}
                                    placeholder="Enter OTP"
                                    maxLength={10}
                                    disabled={!detailReady || selectedUnderReview}
                                />
                                <button
                                    type="button"
                                    className="btn-primary"
                                    onClick={handleConfirm}
                                    disabled={
                                        !detailReady || !otp || loading || selectedUnderReview
                                    }
                                >
                                    {loading ? 'Confirming…' : 'Confirm'}
                                </button>

                                {/* Never disabled by the review: a held payment has no expiry
                                    of its own, so this is the customer's only way out of the
                                    queue if nobody works it. It IS disabled until the payment is
                                    on screen, for the reason above: cancelling the payment you are
                                    not reading is the worst of the three ways this went wrong.

                                    Destructive, and drawn as one: an edge and a label, never a
                                    fill. It is also pushed to the far end of the row, so the gap
                                    itself says it is not one of the pair that finishes the
                                    payment - and that is where the confirmation step will
                                    attach. */}
                                <button
                                    type="button"
                                    className="btn-secondary btn-secondary--danger push-end"
                                    onClick={handleCancel}
                                    disabled={!detailReady || loading}
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

                            {/* Errors related to authorization confirmation. No retry on this box:
                                see the sentence in handleConfirm about what a second press costs. */}
                            {confirmError && <ErrorBox failure={confirmError} />}

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
