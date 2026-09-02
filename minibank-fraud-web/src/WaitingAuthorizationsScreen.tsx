import { Fragment, useEffect, useEffectEvent, useRef, useState, type ReactNode } from 'react';
import {
    cancelTransfer,
    confirmAuthorization,
    fetchTransferDetails,
    fetchWaitingTransfers,
    formatMoney,
    isUnderReview,
    type AuthorizePaymentResult,
    type Page,
    type TransferDetails,
    type WaitingTransferItem,
} from './api';
import ErrorBox from './ErrorBox';
import { describeApiFailure, type ApiFailure } from '@shared/apiErrors';
import {
    REFRESH,
    REFRESH_BUSY,
    TIMES_ZONE_NOTE,
    attemptsLeftSentence,
    authMethodText,
    authWindowNote,
    bankBoundaryLabel,
    describeDeclineReason,
    dispatchStateLabel,
    transferStatusLabel,
    transferStatusTone,
} from '@shared/glossary';
import { authWindowState, formatDateTime, formatIban, formatTransferId } from '@shared/format';
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

/**
 * The name each fact carries on its card.
 *
 * A card is not a table and has no heading row, so a value with no word in front of it is a value
 * nobody has been told the meaning of. `Card` on its own beside a date says nothing; `Auth  Card`
 * says what was asked. The analyst's cards go without names because an analyst reads that queue
 * every day and knows the shape of a row by heart; a customer opens this screen when a payment is
 * waiting, which is rarely, and the queue is a few payments rather than a page of them, so the
 * names cost room this card has to spare.
 *
 * Four and not six. The code and the amount are on the card's first line and take no name, because
 * a payment code and a sum of money say what they are, and because that line is what the eye lands
 * on when it is choosing between cards.
 */
const WAITING_FACT = {
    toIban: 'Beneficiary IBAN',
    createdAt: 'Created',
    authMethod: 'Auth',
    status: 'Status',
} as const;

/**
 * What the tray says when it is holding nothing, and why it is holding nothing.
 *
 * Both stand inside the tray rather than above it, which is the analyst's queue's rule and the
 * reason it exists: a failure drawn over the list left the list underneath still saying there was
 * nothing waiting, so one question got two answers and the second one was wrong.
 */
const LIST_LOADING = 'Loading waiting transfers…';
const LIST_EMPTY = 'No waiting transfers.';

/**
 * How many waiting payments arrive at a time.
 *
 * The server's own default, and larger than the history's five: a work list is meant to be seen
 * entire, and what a customer wants at its foot is to have finished reading rather than to go on.
 */
const PAGE_SIZE = 25;

/**
 * The one sentence a customer whose payment is held needs. It is drawn where the code box would be
 * rather than only as an error, because the press that would produce the error is the one this
 * screen is refusing, so as an error alone it would be copy nobody ever reads.
 *
 * Word for word the TRANSFER_UNDER_REVIEW entry of the shared error table, which is where the same
 * sentence is written for the case where the server does refuse a confirmation. It cannot be read
 * from there without inventing a failure that did not happen, so it is kept here and kept
 * identical, and both platforms hold the same two copies of it.
 */
const UNDER_REVIEW_TEXT =
    'The bank is reviewing this payment. You will be able to confirm it once the review is ' +
    'finished, or you can cancel it.';

/**
 * What a press on Confirm with an empty box is answered with.
 *
 * Confirm is live on an empty box, and that is deliberate: an empty box is the state this screen
 * opens in, and with the button dead the only control in the lower half wearing a colour would be
 * the one that refuses the payment, so the eye would land on the way out of a screen whose whole
 * purpose is the way through. The control that carries the accent has to answer a press.
 *
 * It says what to do rather than what went wrong, because nothing has gone wrong: the customer
 * pressed the right button one step early. Nothing is sent and no attempt is spent, which is why it
 * is a refusal under the box rather than the failure box at the foot of the pane.
 */
const CODE_MISSING_NOTE = 'Enter the one-time code for this payment, then press Confirm.';

/** The box a refusal is drawn under, and the sentence the box points at with aria-describedby. */
const OTP_FIELD_ID = 'auth-otp';
const OTP_ERROR_ID = 'auth-otp-error';

/** The two panes, named by their own headings the way the case panes next door are. */
const LIST_TITLE_ID = 'waiting-list-title';
const DETAIL_TITLE_ID = 'waiting-detail-title';

/**
 * The last thing the bank did to the open payment, together with which press asked for it.
 *
 * One state and not two, because the two have to be read as one sentence. Cancelling and confirming
 * answer with the same shape, and a cancelled payment comes back DECLINED: written apart, the panel
 * announces a refusal to somebody who was never refused.
 */
type Outcome = {
    asked: 'authorization' | 'cancellation';
    result: AuthorizePaymentResult;
};

/**
 * The one fact this pane names differently from the shared map, and it is a difference of audience
 * rather than a second word for a field. The tables call it the decline reason, which is what an
 * analyst filtering payments looks for; the person in front of this screen is the customer whose
 * payment it was, reading one sentence about one payment of their own.
 */
const CUSTOMER_DETAIL_LABEL: Partial<Record<TransferDetailField, string>> = {
    declineReason: 'Why it was stopped',
};

/**
 * The one figure this screen is about takes the lead rung, and the name is the case pane's own.
 * There is no second name for that rung in this window: .figure-value is what the pane beside these
 * screens already sets at --text-6, bold and tabular.
 */
const DETAIL_VALUE_CLASS: Partial<Record<TransferDetailField, string>> = {
    amount: 'figure-value',
};

/**
 * One payment read in full, a value per fact.
 *
 * Built through the shared row type, so a fact the server sends and this pane forgets is a build
 * failure rather than something a customer discovers is missing.
 *
 * `null` is a fact with nothing to say and it is drawn as no line at all. There is no settlement
 * instant on a payment that has not settled, no onward leg on one credited inside this bank, and no
 * reference where the customer typed none. A dash in their place would read as a value withheld.
 */
function detailCells(d: TransferDetails): TransferDetailCells<ReactNode | null> {
    return {
        fromIban: formatIban(d.fromIban),
        fromBalance: formatMoney(d.fromBalance),
        // Where the account is held, beside the account it is about. A payment that has left the
        // bank cannot be pulled back, and this pane stands over the control that cancels one, so
        // the fact belongs on the screen where that choice is made.
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
        declinedAt: d.declinedAt ? formatDateTime(d.declinedAt) : null,
        // The empty string is what the glossary answers for a null, and it stands for three
        // different situations rather than a state: money credited inside this bank, a payment that
        // has not settled, and every row written before the column existed. None of the three may
        // be drawn as "the money stayed here", which is what the To line above answers instead.
        dispatchState: dispatchStateLabel(d.dispatchState) || null,
        authMethod: authMethodText(d.authMethod),
        message: d.message || null,
        declineReason: d.declineReason ? describeDeclineReason(d.declineReason) : null,
    };
}

/**
 * ONE WORKING CYCLE: CHOOSE, READ, ACT.
 *
 * Two panes, and the list is the wide one. That is the fraud desk reversed and it is measured
 * rather than preferred: the list is six columns with a full IBAN among them and spends every pixel
 * it is given, while the chosen payment is nine short facts and two sentences, none of which may
 * pass the reading measure.
 *
 * Weight goes to the coloured status word in the list, to the amount in the facts, and to Confirm.
 * It is withheld from the control that destroys the payment: Cancel transfer stands on a line of
 * its own, below the pair that completes the work, wearing an edge and never a fill.
 *
 * The logic is the customer application's, call for call, because owner decision 1 says a customer
 * is offered the same fields and the same actions on both platforms. What differs is the markup and
 * the skin; every word, format and error sentence is read from the shared layer.
 */
export default function WaitingAuthorizationsScreen() {
    const [items, setItems] = useState<WaitingTransferItem[]>([]);

    /**
     * The page envelope as it last arrived, so the next request is asked for by the page that came
     * back rather than by the number of rows on screen. Those two part company the moment a row
     * arrives twice and is dropped; see appendPage.
     */
    const [last, setLast] = useState<Page<WaitingTransferItem> | null>(null);
    const [selectedId, setSelectedId] = useState<number | null>(null);
    const [details, setDetails] = useState<TransferDetails | null>(null);
    const [otp, setOtp] = useState('');
    const [outcome, setOutcome] = useState<Outcome | null>(null);

    /**
     * Whether the customer has asked to cancel and has not yet said so twice.
     *
     * Cancelling is the one press here that cannot be undone: the server declines the payment, and
     * nothing in this application brings a declined payment back. The step is a panel on the pane
     * and not a dialog over it, per owner decision 14: what the customer needs in front of them to
     * answer is the payment itself, which is in the section above.
     */
    const [confirmingCancel, setConfirmingCancel] = useState(false);

    /**
     * Which payment is open, readable from inside a request that started before it.
     *
     * The state is not: a handler closes over the value it had when it ran, so an answer arriving
     * after the customer has clicked another row has no way to tell that it is late. Every read of
     * the detail is checked against this before it reaches the screen.
     */
    const selectedRef = useRef<number | null>(null);

    /*
     * Three failures, three places, because they are three different statements. The list's own
     * failure stands where the list would be; the detail's stands in the pane that would have read
     * the payment; a refused confirm or cancel stands under the buttons that were pressed.
     */
    const [listError, setListError] = useState<ApiFailure | null>(null);
    const [detailError, setDetailError] = useState<ApiFailure | null>(null);
    const [confirmError, setConfirmError] = useState<ApiFailure | null>(null);

    /**
     * Whether Confirm has been pressed with nothing in the box.
     *
     * Its own flag rather than a confirmError, because nothing was refused and nothing was sent:
     * the three failures above are answers from the bank, and this is the screen answering for
     * itself. It is cleared by the first character typed and by opening another payment, so the
     * sentence never outlives the state it describes.
     */
    const [codeMissing, setCodeMissing] = useState(false);

    const [loading, setLoading] = useState(false);
    const [refreshing, setRefreshing] = useState(false);

    /**
     * Whether a first page of the list is in flight, and it starts true. Without it the screen
     * draws an in-flight list as an empty one: "No waiting transfers." over an API answering with
     * two. The rows on screen outrank it below, so a reload after a confirm does not blank a table
     * somebody is reading.
     */
    const [loadingList, setLoadingList] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);

    /** Whether the selected payment is being read right now. */
    const [loadingDetail, setLoadingDetail] = useState(false);

    /**
     * The instant this screen is drawing at.
     *
     * The minutes a payment has to be confirmed in are the one thing here that changes with nothing
     * arriving from the server, so they are the one thing that needs a clock behind them. Everything
     * read off it is read at render time from this single value, which keeps the sentence under the
     * controls and the controls themselves from disagreeing about what time it is.
     */
    const [now, setNow] = useState(() => new Date());

    /*
     * THE FIRST READ OF THE LIST, SEPARATED FROM WHAT MAKES IT HAPPEN.
     *
     * What makes it happen is the screen opening, once, and nothing else. loadList cannot be named
     * as a dependency to say that: it reads items.length, to ask for a page as large as the list
     * already on screen, and selectedId, to drop a selection that has left it, so it is a new value
     * whenever either changes and an effect naming it honestly would re-read the whole list every
     * time a customer picked a payment. useCallback only moves that into its own dependency list;
     * keeping it mount-only through one means reading those two through refs instead, which is a
     * second copy of two pieces of state kept in step by hand for the sake of a lint rule.
     *
     * useEffectEvent is the hook for exactly this. What it wraps is always the latest version and
     * is never a dependency, so the array below is empty because the effect is a mount - and it is
     * exhaustive, rather than silenced into looking that way.
     */
    const loadListOnMount = useEffectEvent(() => {
        void loadList();
    });

    useEffect(() => { loadListOnMount(); }, []);

    /* The deadline of the payment on screen, which is what the tick below is started for. */
    const authDeadline = details?.authValidUntil ?? null;

    /**
     * One interval, and it runs only while there is a window to count. Started for a payment that
     * has a deadline and stopped the moment that deadline passes or the payment is put away: a
     * timer left running behind a screen with nothing to count is a render a second for nothing,
     * and this screen can sit open for as long as a customer likes.
     */
    useEffect(() => {
        if (!authDeadline) return;

        // Taken here rather than left at whatever the last payment ticked to. A payment selected
        // between two ticks would otherwise be read against a clock up to a second stale, and at
        // the end of a window that second is the difference between a live Confirm and a dead one.
        setNow(new Date());
        if (authWindowState(authDeadline, new Date()) === 'closed') return;

        const tick = setInterval(() => {
            const at = new Date();
            setNow(at);
            // Nothing left to count once it has closed. What the screen says about it is decided in
            // the render below; this only stops the clock that fed it.
            if (authWindowState(authDeadline, at) === 'closed') clearInterval(tick);
        }, 1000);

        return () => clearInterval(tick);
    }, [authDeadline]);

    /**
     * Whether the window has closed, recomputed every render because `now` is what moves. It kills
     * the code box and Confirm and it kills nothing that gets the customer out of the payment:
     * pressing Confirm past the deadline is not a wasted click, it is the server declining the
     * payment outright.
     */
    const windowClosed = authWindowState(authDeadline, now) === 'closed';

    // The selected row as the list last reported it. The whole point of the status is that the
    // customer can see why Confirm is gone, so it has to come from the row rather than from
    // `details`, which may not have loaded yet.
    const selectedItem = items.find((x) => x.id === selectedId) ?? null;
    const selectedUnderReview =
        isUnderReview(selectedItem?.status) || isUnderReview(details?.status);

    // Built once per render rather than once per field: the pane below walks the field list, and
    // calling the builder inside that walk would rebuild every fact once per fact.
    const detailFacts = details ? detailCells(details) : null;

    /**
     * Whether the open payment can still be given a code. The box and Confirm are drawn on this and
     * on nothing else, so the two states that refuse a code do not leave a dead pair standing.
     *
     * A payment with no window at all is not one of the two: released from review, it waits for its
     * code with no time limit, which is a state that takes codes.
     */
    const codeStillTaken = !windowClosed && !selectedUnderReview;

    /**
     * The one sentence saying why no code can be given, or nothing while one can.
     *
     * The closed window is asked first and answers alone. It is the terminal state of the two, and
     * the review sentence promises that confirming becomes possible again once the review ends,
     * which is a promise nobody can keep about a payment whose window has already run out.
     */
    const blockingNote = windowClosed
        ? authWindowNote(authDeadline, now)
        : selectedUnderReview
            ? UNDER_REVIEW_TEXT
            : null;

    /**
     * Whether the payment in the pane is the payment the buttons would act on. It is false while a
     * detail is in flight and false after one has failed, and both of those disable Confirm and
     * Cancel: keyed to the selection alone, the customer could cancel a payment whose amount was
     * not the one in front of them.
     */
    const detailReady = details !== null && !loadingDetail;

    /**
     * Manual refresh. A held payment is released by somebody else, at a time the customer is not
     * told about, and nothing here polls: loadList runs on mount and after the customer's own
     * confirm or cancel.
     */
    async function handleRefresh() {
        try {
            setRefreshing(true);
            await loadList();
            // Refreshed together, because the pane is a reading of one row of the list beside it.
            const open = selectedRef.current;
            if (open != null) {
                await loadDetails(open);
            }
        } finally {
            setRefreshing(false);
        }
    }

    /**
     * Reads one payment in full, and puts it on screen only if it is still the one selected. The
     * single door to fetchTransferDetails. The id is checked before the request as well as after
     * it, because loadList clears the selection when the payment has left the list.
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
            // The payment goes with its failure. A pane that keeps the last readable version of a
            // payment while saying it could not be read is the state this guard is about.
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
     * first page of twenty-five. The ceiling is the server's.
     */
    async function loadList() {
        try {
            setListError(null);
            setLoadingList(true);
            const wanted = Math.min(MAX_PAGE_SIZE, Math.max(PAGE_SIZE, items.length));
            const answer = await fetchWaitingTransfers(0, wanted);
            setItems(answer.items);
            setLast(answer);
            // A selection that has left the list goes, and the ref goes with the state: that is
            // what makes a detail request started before this drop its answer instead of drawing a
            // payment that is no longer selected.
            if (selectedId && !answer.items.some((x) => x.id === selectedId)) {
                selectedRef.current = null;
                setSelectedId(null);
                setDetails(null);
                setDetailError(null);
                setOtp('');
                setCodeMissing(false);
                // The question was about the payment that has just left the list, so it goes with
                // it. Left standing it would be a confirmation with nothing selected behind it.
                setConfirmingCancel(false);
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
     * The next page, appended under the rows already on screen. A failure here leaves those rows
     * where they are and returns the button to its resting label, so it can be pressed again.
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
            // difference between this box and the one standing where the list would be.
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
     * Everything below the selection belongs to the row that was selected: the payment in the pane,
     * the code half typed into the box, the refusal of the last confirm and the receipt of the last
     * one that worked. Carried across, each of them is a statement about one payment standing under
     * another, and the code is the dangerous one, since Confirm would send it.
     */
    async function handleSelect(id: number) {
        selectedRef.current = id;
        setSelectedId(id);
        setDetails(null);
        setDetailError(null);
        setOutcome(null);
        setConfirmError(null);
        setOtp('');
        setCodeMissing(false);
        setConfirmingCancel(false);
        await loadDetails(id);
    }

    async function handleConfirm() {
        if (!selectedId) return;

        // The one guard that answers instead of doing nothing. Confirm stays live while a code can
        // still be taken, so this is a reachable press and a silent return would read as a button
        // that does not work.
        if (!otp) {
            setCodeMissing(true);
            return;
        }
        setCodeMissing(false);

        try {
            setLoading(true);
            setConfirmError(null);

            const res = await confirmAuthorization({ transferId: selectedId, otp });
            setOutcome({ asked: 'authorization', result: res });

            // The list, because a payment that is no longer waiting has left it.
            await loadList();

            // And the payment, for the attempts and the deadline it now holds. One that has just
            // been sent has left the list, so loadList above has cleared the selection and this
            // reads nothing: the receipt below is what says what happened to it.
            await loadDetails(selectedId);

            // A wrong code does not arrive here: it is a 400 and lands in the catch below, so
            // WAITING_AUTH is not a possible outcome of a 200. The third failed attempt and an
            // expired window still arrive as 200 DECLINED, which is why this branch stays.
            if (res.status === 'DECLINED') {
                // Not a failed call: the bank answered, and this is what it answered. There is no
                // code to print under it and nothing to try again.
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
            // A refused authorization still changed the transfer: a wrong code costs one of the
            // three attempts. Refresh before reporting, so the count under the controls and the
            // count inside the sentence are the ones the server now holds.
            await loadList();
            const d = await loadDetails(selectedId);

            // No retry on this one, and none on the cancel below either. Confirming is not a read:
            // a second press is a second attempt against a counter of three.
            setConfirmError(
                describeApiFailure(e, 'payment-authorize', { triesLeft: d?.triesLeft }),
            );
        } finally {
            // Cleared on every outcome, including the refusal: a known-bad code left in the box
            // with Confirm still live is two impatient clicks away from burning the transfer.
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
            // Announced as a cancellation and not as an authorization result. The bank answers
            // DECLINED to a cancellation, because refusing the payment is how a cancellation is
            // carried out, and that word belongs to the code path: reading it here, a customer who
            // has just cancelled would be told the bank turned their payment down.
            setOutcome({ asked: 'cancellation', result: res });

            await loadList();
            await loadDetails(selectedId);
        } catch (e) {
            setConfirmError(describeApiFailure(e, 'payment-cancel'));
        } finally {
            // On both outcomes. The question has been answered, and after a refusal it must be
            // asked again rather than left standing over a payment that is still waiting.
            setConfirmingCancel(false);
            setLoading(false);
        }
    }

    return (
        <>
            {/*
              THE LIST, AND IT IS THE WIDE PANE. Six columns with a full IBAN among them, against a
              chosen payment that is short facts and prose. Named by its own heading rather than by
              a string written here, so the two can never come apart.
            */}
            <section className="panel left section" aria-labelledby={LIST_TITLE_ID}>
                <h2 className="section-title" id={LIST_TITLE_ID}>
                    Waiting transfers
                </h2>

                {/*
                  THE SAME TRAY THE ANALYST'S QUEUE IS, and for the same reasons.

                  A bordered box that scrolls inside itself, holding one card per payment. The
                  window keeps its height and the queue moves within it, so the chosen payment and
                  the controls that act on it never leave the screen because the list grew; a table
                  did the opposite, pushing the whole screen down a row at a time.

                  What is on a card is the customer's own question and nothing else. The desk's
                  cards carry a risk score, an assignee and the sentence that raised the alert
                  because triage turns on those; none of them is a fact about a payment somebody is
                  waiting to confirm, and none of them appears here. What is here is what the table
                  before it showed: which payment, where the money is going, how much, when it was
                  asked for and how it will be authorized.

                  EVERYTHING THE LIST HAS TO SAY IS INSIDE THE TRAY, which is the desk's rule and
                  not a new one: the rows, the reason there are none, and the failure to fetch them
                  are three answers to one question, and putting the failure above the tray left the
                  tray underneath still saying there was nothing waiting. Nothing is said at all
                  while a re-read runs under rows already on screen: those rows are the list as it
                  last answered, and blanking them to print "Loading" is how a refresh loses
                  somebody their place.
                */}
                <div className="list">
                    {items.map((it) => (
                        /*
                          A button, which is what the desk's cards are. The row it replaces had to
                          be given a tab stop, a role and two key handlers to behave like one; a
                          button is one already. aria-pressed stays, because which payment is open
                          is a fact a reader cannot take from the marker down the card's edge.
                        */
                        <button
                            key={it.id}
                            type="button"
                            className={
                                selectedId === it.id
                                    ? 'list-item list-item--active'
                                    : 'list-item'
                            }
                            aria-pressed={selectedId === it.id}
                            onClick={() => void handleSelect(it.id)}
                        >
                            {/* The first line of the card: what the payment is called and what it
                                is worth, pushed to the two edges so that the codes and the amounts
                                form two columns down the whole tray. Neither takes a name. */}
                            <div className="li-top">
                                <div className="li-code">{formatTransferId(it.id)}</div>
                                <div className="li-amount">{formatMoney(it.amount)}</div>
                            </div>

                            {/* The rest of the card, each fact beside its own word. One grid for
                                the four of them, so the words make a column and the values make a
                                column, and the eye can run down either. */}
                            <div className="li-facts">
                                {/* The treatment comes with the word: a payment the bank is
                                    holding and one waiting for a code are two different things to
                                    a customer looking for what to do next. */}
                                <span className="li-fact-name">{WAITING_FACT.status}</span>
                                <span className={`tone-${transferStatusTone(it.status)}`}>
                                    {transferStatusLabel(it.status, 'customer')}
                                </span>

                                <span className="li-fact-name">{WAITING_FACT.toIban}</span>
                                <span>{formatIban(it.toIban)}</span>

                                <span className="li-fact-name">{WAITING_FACT.createdAt}</span>
                                <span>{formatDateTime(it.createdAt)}</span>

                                {/* The words and not a dash: a payment waiting for its code has no
                                    auth method yet, which is a fact about the payment rather than a
                                    value this screen failed to fetch. */}
                                <span className="li-fact-name">{WAITING_FACT.authMethod}</span>
                                <span>{authMethodText(it.authMethod)}</span>
                            </div>
                        </button>
                    ))}

                    {items.length > 0 ? null : listError ? (
                        <ErrorBox failure={listError} />
                    ) : (
                        <div className="hint">
                            {loadingList ? LIST_LOADING : LIST_EMPTY}
                        </div>
                    )}

                    {/* The way to lengthen the queue, drawn as the last thing IN the tray rather
                        than under it: a control that says there is more belongs where the rows run
                        out, which is where the eye is when they do. Removed rather than disabled
                        once the whole list is on screen - a dead control still invites the press
                        that proves it, and the count line under the tray says so in words. */}
                    {hasMore(items.length, last?.total ?? 0) && (
                        <button
                            type="button"
                            className="btn list-more"
                            onClick={() => void loadMore()}
                            disabled={loadingMore}
                            aria-busy={loadingMore || undefined}
                        >
                            {loadingMore ? SHOW_MORE_BUSY : SHOW_MORE}
                        </button>
                    )}
                </div>

                {/*
                  THE FOOT OF THE QUEUE, and it is drawn at every moment.

                  What is on screen, and the way to ask again, on one line under the tray. Refreshing
                  belongs here and not above the list: it and the count go stale together, and a
                  control that asks the list again standing over the list reads as something to do
                  before reading rather than after. The count is not always there - an empty tray
                  says so in words inside itself - so the line is flex-end and the button holds the
                  right whether or not anything is counted beside it.
                */}
                <div className="queue-line">
                    {items.length > 0 && (
                        <div className="hint">
                            {showingLine(items.length, last?.total ?? 0)}
                            {/* The zone rides the same line as the count, both being facts about
                                the list rather than about any payment in it. */}
                            <span className="meta-sep">{TIMES_ZONE_NOTE}</span>
                        </div>
                    )}
                    <button
                        type="button"
                        className="btn"
                        onClick={() => void handleRefresh()}
                        disabled={refreshing}
                        aria-busy={refreshing || undefined}
                    >
                        {refreshing ? REFRESH_BUSY : REFRESH}
                    </button>
                </div>

                {/* The page that did not arrive, under the rows that did. It is about the request
                    rather than about the list, which is why it stands here and not in the tray. */}
                {items.length > 0 && listError && <ErrorBox failure={listError} />}
            </section>

            {/* THE CHOSEN PAYMENT, and what can still be done with it. The narrow pane: nine short
                facts and two sentences, none of which may pass the reading measure. */}
            <main className="panel right">
                <section className="section" aria-labelledby={DETAIL_TITLE_ID}>
                    <h2 className="section-title" id={DETAIL_TITLE_ID}>
                        Selected transfer details
                    </h2>
                    <div className="section-block">
                        {/*
                          The same rule as the list, in the same order: what is on its way, then why
                          nothing came, then the payment, then the sentence for a screen where
                          nothing is selected.
                        */}
                        {loadingDetail ? (
                            <p className="helper-text">Loading payment…</p>
                        ) : detailError ? (
                            <ErrorBox failure={detailError} />
                        ) : details ? (
                            /*
                             * A definition list, which is what this window spends on a pane read
                             * many times a day: the labels stand in the same track the filter rows
                             * and the decision footer use, so one vertical line runs down the pane.
                             * The order is the shared field list's and not this file's, and the one
                             * figure the screen is about takes the lead rung.
                             */
                            <dl className="facts">
                                {TRANSFER_DETAIL_FIELDS.map((f) => {
                                    const value = detailFacts?.[f] ?? null;
                                    if (value === null) return null;

                                    return (
                                        <Fragment key={f}>
                                            <dt>
                                                {CUSTOMER_DETAIL_LABEL[f] ??
                                                    TRANSFER_DETAIL_LABEL[f]}
                                            </dt>
                                            <dd className={DETAIL_VALUE_CLASS[f]}>{value}</dd>
                                        </Fragment>
                                    );
                                })}
                            </dl>
                        ) : (
                            <p className="helper-text">No transfer selected.</p>
                        )}
                    </div>
                </section>

                <section className="section">
                    {/*
                      The heading names the question rather than one of the two answers. "Confirm
                      authorization" would stand over a payment where confirming is exactly what can
                      no longer be done: the section named after the half of itself that dies.
                    */}
                    <h2 className="section-title">What you can do now</h2>

                    <div className="section-block">
                        {/*
                          Why the code box is not on the screen, above the place it is missing from
                          and in the weight of a fact. Under the row it explains, in the quietest
                          type the window owns, it would arrive after the effect and weigh less
                          than it.
                        */}
                        {blockingNote && <p className="state-note">{blockingNote}</p>}

                        {/*
                          The pair is drawn while a code can still be taken, and not drawn
                          otherwise. Disabling it was the older answer and it cost the screen its
                          rank: a dead box and a dead Confirm left the one live control here to be
                          the one that refuses the payment. The sentence above says why the pair is
                          gone; the list removes its own Show more for the same reason.

                          What is left of the guard is the payment itself: every control here is
                          dead until the payment it would act on is the payment on screen.
                        */}
                        {codeStillTaken && (
                            <>
                                <div className="field-row">
                                    {/*
                                      Named for a screen reader and not for the eye. The box beside
                                      it already says what goes in it, so the word was the same
                                      instruction printed twice, and the second copy was taking a
                                      column of the row to do it. The name still has to exist:
                                      an unnamed box is announced as "edit text" and nothing more.
                                    */}
                                    <label className="visually-hidden" htmlFor={OTP_FIELD_ID}>
                                        One-time code
                                    </label>
                                    <input
                                        id={OTP_FIELD_ID}
                                        className="otp-input"
                                        type="text"
                                        inputMode="numeric"
                                        autoComplete="one-time-code"
                                        value={otp}
                                        onChange={(e) => {
                                            setOtp(e.target.value);
                                            setCodeMissing(false);
                                        }}
                                        placeholder="Enter OTP"
                                        maxLength={10}
                                        aria-invalid={codeMissing || undefined}
                                        aria-describedby={codeMissing ? OTP_ERROR_ID : undefined}
                                        disabled={!detailReady}
                                    />
                                    {/*
                                      Live on an empty box, and that is the point rather than an
                                      oversight: an empty box is the state this screen opens in, and
                                      the only other control below the facts is the one that refuses
                                      the payment. A control carrying the screen's one accent has to
                                      answer a press, and the refusal under the box is the answer.

                                      Still dead until the payment is on screen, and while a
                                      confirmation is in flight. Those two are about the payment
                                      rather than about the code.
                                    */}
                                    <button
                                        type="button"
                                        className="btn btn--primary"
                                        onClick={() => void handleConfirm()}
                                        disabled={!detailReady || loading}
                                    >
                                        {loading ? 'Confirming…' : 'Confirm'}
                                    </button>

                                    {/* Under the box it is about, on a line of its own, and only
                                        after the press that earns it: a screen saying what is
                                        missing before anything has been asked of it is a screen
                                        that opens with a complaint. */}
                                    {codeMissing && (
                                        <p className="field-error" id={OTP_ERROR_ID} role="alert">
                                            {CODE_MISSING_NOTE}
                                        </p>
                                    )}
                                </div>

                                {/*
                                  What this payment has left, in sentences rather than in labels and
                                  values. Both facts belong to a transfer asking for a code and the
                                  server sends them as null on one that is not, so the block is
                                  drawn only where there is something to say.
                                */}
                                {(details?.triesLeft != null || authDeadline != null) && (
                                    <div className="gap-above-sm">
                                        {/* The last attempt changes tone, in the amber this window
                                            already paints a stalled payment in. Not the red of a
                                            refusal: nothing has been refused while a live attempt
                                            remains, and a customer who read red here would read it
                                            as the payment already lost. */}
                                        {details?.triesLeft != null && (
                                            <p
                                                className={
                                                    details.triesLeft === 1
                                                        ? 'helper-text tone-pending'
                                                        : 'helper-text'
                                                }
                                            >
                                                {attemptsLeftSentence(details.triesLeft)}
                                            </p>
                                        )}

                                        {/* One sentence, chosen by the state of the window and
                                            written in the glossary with the other two. A label and
                                            a fixed timestamp could say that a moment eight days
                                            gone is when this payment will expire. */}
                                        <p className="helper-text gap-above-sm">
                                            {authWindowNote(authDeadline, now)}
                                        </p>
                                    </div>
                                )}
                            </>
                        )}
                    </div>

                    {/* THE WAY OUT, ON A LINE OF ITS OWN.

                        Never disabled by the review: a held payment has no expiry of its own, so
                        this is the customer's only way out of the queue if nobody works it. It IS
                        disabled until the payment is on screen: cancelling the payment you are not
                        reading is the worst way this can go wrong.

                        Destructive, and drawn as one: an edge and a label, never a fill. Pushed to
                        the far end of the row above instead, the gap meant to say "not one of the
                        pair" would say "look here" first. It no longer cancels anything either: it
                        asks, and the answer is the panel below. Dead while that panel stands,
                        because the panel carries a control that does the same thing. */}
                    <div className="section-block exit-row">
                        <button
                            type="button"
                            className="btn btn--danger"
                            onClick={() => setConfirmingCancel(true)}
                            disabled={!detailReady || loading || confirmingCancel}
                        >
                            Cancel transfer
                        </button>
                    </div>

                    {/*
                      The second step, and it is inline rather than modal per owner decision 14:
                      what the customer needs in order to answer is the payment itself, and it is in
                      the pane a few rows above. A dialog would cover it and ask them to remember.

                      It names the payment and the amount rather than saying "this transfer". The
                      amount comes from `details`, the same reading the pane above prints, so the
                      two cannot name different payments.
                    */}
                    {confirmingCancel && selectedId != null && details && (
                        <div className="summary summary--danger gap-above-md">
                            <div className="summary-title">
                                Cancel {formatTransferId(selectedId)}?
                            </div>
                            <p className="helper-text">
                                {formatMoney(details.amount)} to {formatIban(details.toIban)}. The
                                payment is refused for good: nothing is taken from your account, and
                                it cannot be brought back. Sending the same money again means making
                                a new payment.
                            </p>
                            <div className="summary-actions">
                                <button
                                    type="button"
                                    className="btn btn--danger"
                                    onClick={() => void handleCancel()}
                                    disabled={!detailReady || loading}
                                >
                                    {loading ? 'Cancelling…' : 'Cancel this payment'}
                                </button>
                                {/* Decides nothing and undoes nothing, so it carries no shape: it
                                    puts the screen back as it was, with the payment still waiting
                                    and the code box still live. */}
                                <button
                                    type="button"
                                    className="btn btn--quiet"
                                    onClick={() => setConfirmingCancel(false)}
                                    disabled={loading}
                                >
                                    Keep this payment
                                </button>
                            </div>
                        </div>
                    )}

                    {/* No retry on this box: see the sentence in handleConfirm about what a second
                        press costs. */}
                    {confirmError && <ErrorBox failure={confirmError} />}

                    {/*
                      What was asked for chooses the panel, not what the status word says. A
                      cancellation gets its own heading and its own two facts: the status, the tone
                      and the reason of the panel below all read a refusal off DECLINED, which is
                      the truth about a code that failed and a lie about a payment the customer
                      withdrew themselves.
                    */}
                    {outcome?.asked === 'cancellation' && (
                        /* To the customer's knowledge and not a success: nothing was sent, nothing
                           was taken, and the payment they asked to be rid of is gone. The edge that
                           says a thing worked belongs to the presses that move money. */
                        <div className="summary summary--neutral gap-above-md" role="status">
                            <div className="summary-title">Payment cancelled</div>
                            <ul>
                                <li>
                                    Transfer: {formatTransferId(outcome.result.transferId)}
                                </li>
                                {/* Nothing left the account, so this is the balance it has had all
                                    along and is printed to say so. */}
                                <li>
                                    Current balance: {formatMoney(outcome.result.newBalance)}
                                </li>
                            </ul>
                        </div>
                    )}

                    {outcome?.asked === 'authorization' && (
                        /* The edge follows the answer and not the request. The same box reports a
                           payment on its way and a payment the bank refused for a code that did not
                           match, and the second is a failure that arrives as a perfectly good
                           answer, so the error box never sees it. A reader has to be able to tell
                           those two apart before they have read a word. */
                        <div
                            className={`summary ${
                                outcome.result.status === 'SENT'
                                    ? 'summary--success'
                                    : 'summary--danger'
                            } gap-above-md`}
                            role="status"
                        >
                            <div className="summary-title">
                                {outcome.result.status === 'SENT'
                                    ? 'Payment authorized'
                                    : 'Authorization result'}
                            </div>
                            <ul>
                                <li>
                                    Transfer: {formatTransferId(outcome.result.transferId)}
                                </li>
                                <li>
                                    Status:{' '}
                                    <span
                                        className={`tone-${transferStatusTone(
                                            outcome.result.status,
                                        )}`}
                                    >
                                        {transferStatusLabel(outcome.result.status, 'customer')}
                                    </span>
                                </li>

                                {/* Only where funds were actually debited. */}
                                {outcome.result.chargedAmount && (
                                    <li>Charged: {formatMoney(outcome.result.chargedAmount)}</li>
                                )}

                                {/* newBalance is always current; the wording follows the status. */}
                                <li>
                                    {outcome.result.status === 'SENT'
                                        ? 'New balance: '
                                        : 'Current balance: '}
                                    {formatMoney(outcome.result.newBalance)}
                                </li>

                                {outcome.result.declineReason && (
                                    <li>
                                        Reason:{' '}
                                        {describeDeclineReason(outcome.result.declineReason)}
                                    </li>
                                )}
                            </ul>
                        </div>
                    )}
                </section>
            </main>
        </>
    );
}
