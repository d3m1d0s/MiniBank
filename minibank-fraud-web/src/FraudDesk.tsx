import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
    fetchAlerts,
    fetchAlertDetail,
    fetchAlertHistory,
    fetchHiddenAlerts,
    formatMoney,
    parseAmount,
    postFraudDecision,
    releaseAlert,
    takeAlert,
    type AlertCounters,
    type AlertDetail,
    type AlertFilters,
    type AlertNote,
    type AlertQueueItem,
    type FraudDecision,
    type HistoryItem,
    type Page,
} from './api';
import { amountRangeProblem } from '@shared/alertFilters';
/*
 * Straight from the shared module rather than through ./api, because the barrel says what this
 * application asks of the API and a fee line is drawn from a value that already arrived.
 */
import { formatFeeLine } from '@shared/money';
/*
 * The address bar, which is where the selection lives. The shell reads it and hands the id down;
 * this desk writes it when the selection moves, and hears its own write back as that prop.
 */
import { goTo, replaceRoute, routeFor } from '@shared/route';
import { describeApiError, describeApiFailure } from '@shared/apiErrors';
import type { ApiFailure } from '@shared/apiErrors';
import ErrorBox from './ErrorBox';
import NavRail from './NavRail';
import {
    ALERT_NOTE_FIELDS,
    ALERT_NOTE_LABEL,
    AMOUNT_FROM_LABEL,
    AMOUNT_PLACEHOLDER,
    AMOUNT_TO_LABEL,
    FIELD_LABEL,
    HISTORY_FIELD_LABEL,
    HISTORY_COLUMN_FIELDS,
    HISTORY_SETTLED_FIELD,
    HISTORY_UNDER_ROW_FIELDS,
    TRANSFER_DETAIL_LABEL,
    type AlertNoteField,
    type AlertNoteRowCells,
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
    ALERTS_QUEUE_TITLE,
    ALERT_DETAILS_TITLE,
    ALERT_DETAIL_LOADING,
    ALERT_NOTES_TITLE,
    ALERT_REASON_LABEL,
    ASSIGNED_TO_ANYONE,
    ASSIGNED_TO_ME,
    CUSTOMER_HISTORY_TITLE,
    DECISION_BUSY,
    DECISION_COMMENT_LABEL,
    DECISION_COMMENT_PLACEHOLDER,
    DECLINE_ALREADY_SENT,
    DECLINE_NEEDS_COMMENT,
    DECISION_NOTE_LABEL,
    DECISION_NOTE_PLACEHOLDER,
    DECISION_RESULT_TITLE,
    DECISION_TITLE,
    NO_ALERT_NOTES,
    NO_HISTORY,
    QUEUE_LOADING,
    REFRESH,
    REFRESH_BUSY,
    RELEASE_ALERT,
    SELECT_ALERT,
    SHOW_WITHDRAWN_ALERTS,
    TAKE_ALERT,
    UNASSIGNED,
    alertStateLabel,
    alertStateTone,
    authMethodLabel,
    bankBoundaryMark,
    bankBoundaryLabel,
    decisionActionLabel,
    decisionLabel,
    describeDeclineReason,
    describeDecision,
    dispatchStateLabel,
    emptyQueueNote,
    hiddenAlertsNote,
    noteAuthorLabel,
    MAX_DECISION_COMMENT,
    MAX_DECISION_NOTE,
    QUEUE_COUNTERS_BASIS_NARROW,
    queueCounterCells,
    queueCounterTotal,
    roleLabel,
    TIMES_ZONE_NOTE,
    transferStatusLabel,
    transferStatusTone,
} from '@shared/glossary';
import {
    SHOW_MORE,
    SHOW_MORE_BUSY,
    appendPage,
    hasMore,
    nextPage,
    showingLine,
} from '@shared/paging';
import { SESSION_IDLE_NOTE, type NavRole } from '@shared/navigation';

/** The transfer status a withdrawn payment ends in. */
const WITHDRAWN = 'DECLINED';

/**
 * The name of the box in the tray that says why the queue is empty.
 *
 * A filter control the desk has refused points at it rather than repeating its sentence: the
 * statement is made once, in the tray, and the box that caused it says which statement is its own.
 */
const QUEUE_ERROR_ID = 'queue-error';

/**
 * The two words on the plate that folds the middle of the decision block away, and the name of
 * the region it folds.
 *
 * Here and not in frontend-shared/glossary.ts, which is where every word both applications say is
 * kept. This control exists on the workstation only: the customer desk is one scrolling column
 * with nothing pinned under a hairline, so it has no such plate and there is no second wording of
 * these strings anywhere for this one to drift from.
 *
 * Each names the press rather than the state, because that is what a button is asked; the state
 * is carried beside them by aria-expanded, and by the caret the plate draws.
 */
const DECISION_FOLD_LABEL = 'Hide the decision boxes';
const DECISION_UNFOLD_LABEL = 'Show the decision boxes';
const DECISION_FOLD_ID = 'decision-fold';

/*
 * What names the queue region, and it is the id of the queue's own heading rather than a second
 * copy of the words: the region and the title it is announced by cannot come apart if there is
 * only one of them. The case pane next to it needs none, because a window has one main region and
 * the tag is the whole of what says so.
 */
const QUEUE_TITLE_ID = 'queue-title';

/*
 * Why Decline is dead while the comment box is empty: DECLINE_NEEDS_COMMENT, now in
 * frontend-shared/glossary.ts. It was written here and again on the customer desk, in two
 * different wordings, which is the drift the shared file exists to prevent.
 */
/*
 * Which clock the times on this screen are told by: TIMES_ZONE_NOTE, now in
 * frontend-shared/glossary.ts, where the wording and the reasons for it live.
 *
 * Said once, at the foot of the tray, rather than on every row. The zone the times carry is an
 * abbreviation, CET in winter and CEST in summer, and the sentence spells that abbreviation out;
 * the two are not the same statement and neither replaces the other.
 */

/**
 * How long a session survives with nothing asked of the bank.
 *
 * SessionStore.IDLE_TIMEOUT is fifteen minutes and it is refreshed by every request the screens
 * make, which is why the number belongs on screen: an analyst who spends twenty minutes writing a
 * decision comment and touches nothing else is signed out at the press of Decline, and until now
 * the rule that did it was written down nowhere they could read.
 *
 * The second half is what the sentence used to get wrong. It read "Typing does not count", and it
 * was true: an analyst who spent twenty minutes writing a decision comment and touched nothing else
 * was signed out at the press of Decline, because only a request refreshed the timer and typing is
 * not a request. That is a rule the bank has no reason to want. The shell now turns real work into
 * a request, so typing counts, and the sentence says so.
 *
 * What it still does not say, because it is no longer true, is that a client holds the session open
 * on its own. Nothing here asks the bank on a timer: the request is made only where somebody has
 * just done something, so a desk left alone asks nothing and is signed out on the server's
 * schedule. See the keep-alive in App.tsx, which is where the session lives.
 *
 * In the title bar, beside the name and the way out, because that is the session's own corner of
 * the window. That row is one line of chrome across the title, the name and the way out, and it is
 * what keeps the second half to two words: a fuller wording of the same fact, measured at 1024 by
 * 768, broke the product name across two lines and split the Sign out button in half. Typing was
 * the case the old sentence had to warn about, so naming it is what the correction needs; a person
 * who reads that typing counts does not go on to wonder about scrolling.
 *
 * Both applications end a session by the same rule and say the same sentence, so it now lives in
 * frontend-shared/navigation.ts beside SIGNED_OUT_NOTICE and is imported from there.
 */

/**
 * How many alerts arrive at a time.
 *
 * The server's own default, and the same number the customer application's desk asks for. A queue
 * is meant to be seen entire, and the question at its foot is how much work is left rather than
 * whether to read on; the count line answers that one and the counters above the tray answer a
 * different one again.
 */
const PAGE_SIZE = 25;

/**
 * How many of the customer's payments arrive with the first look at an alert.
 *
 * Ten, which is what the detail used to carry and all it could ever carry. The table is evidence
 * inside a panel that is scrolled past on the way to the verdict, so it opens at the length it
 * has always had and lengthens on request; a first page of twenty-five would push the decision
 * block's own evidence out of reach to show payments nobody asked for.
 */
const HISTORY_PAGE_SIZE = 10;

/**
 * Which amount bound is being typed. The two boxes are one control by role, so one function
 * handles both and this tells them apart.
 */
type AmountBound = 'min' | 'max';

/**
 * A page of the customer's payments, and the alert it was fetched for.
 *
 * The id travels with the rows because the panel now makes two requests about one alert and the
 * history is the slower of them. Without it a page that arrives after the analyst has clicked the
 * next card is drawn under that card's facts, which is a worse fault than a table that says it is
 * still loading: nothing on the row would say the payments belong to somebody else.
 */
interface HistoryPage {
    alertId: number;
    rows: HistoryItem[];
    last: Page<HistoryItem>;
}

/** A refusal from the history route, carrying the alert it was asked about for the same reason. */
interface HistoryProblem {
    alertId: number;
    failure: ApiFailure;
}

/**
 * What sizes and aligns each history column.
 *
 * WHICH columns there are, and in what order, is no longer written out here: the list comes from
 * the shared field set, and the split between the fields that take a column and the one that
 * takes a row of its own is named there too. A field added to the union now turns up in this
 * table on the next build, where a hand kept list of five would have left it out in silence.
 *
 * What stays local is the width and the alignment. Those are this pane's own: the same five
 * fields are laid across a thousand pixels in the customer application and down a pane with a
 * floor of 360 here, which is the idiom difference the shared set exists to allow.
 *
 * The amount is marked where the cell is built, and the heading takes the same mark from the same
 * entry: alignment belongs to the column, so the word has to stand over its own digits rather than
 * at the far edge of them. Counting header cells to find the column to right align, which is what
 * the customer application's stylesheet still does, breaks silently the day a column is added.
 *
 * The route is account numbers stacked in one column and still carries no cell class: what has to
 * be styled there is each of the lines, and each is marked where it is built, for the same reason
 * the amount is.
 */
const HISTORY_COLUMN_CLASS: Partial<Record<HistoryField, { col: string; cell?: string }>> = {
    id: { col: 'col--id' },
    createdAt: { col: 'col--created' },
    amount: { col: 'col--amount', cell: 'cell--amount' },
    status: { col: 'col--status' },
    route: { col: 'col--route' },
};

/**
 * What sizes each column of the journal.
 *
 * Same shape as the map above and for the same reasons: the order of the columns is the shared
 * field set's, and only the width is this pane's. The two stamps are given what a grouped
 * timestamp and a login need and no more, because the third column is the entry itself and every
 * pixel not spent on the first two is a line of prose that does not wrap.
 *
 * No cell class on any of the three. Nothing here is a number to align and nothing needs a rung of
 * its own inside its cell, which is what the history's amount and its route both did.
 */
const NOTE_COLUMN_CLASS: Record<AlertNoteField, string> = {
    writtenAt: 'col--written',
    author: 'col--author',
    text: 'col--note',
};

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
        // The two words that say whether this row is still somebody's problem, carrying the same
        // treatment they carry in the history table below and in the customer application. Without
        // it the tray was the one place in the product where a cleared alert and a new one were set
        // in the same grey, which is the opposite of what a triage list is for.
        state: <span className={`tone-${alertStateTone(a.state)}`}>{alertStateLabel(a.state)}</span>,
        transferStatus: (
            <span className={`tone-${transferStatusTone(a.transferStatus)}`}>
                {transferStatusLabel(a.transferStatus, 'analyst')}
            </span>
        ),
        amount: formatMoney(a.amount),
        shortReason: a.shortReason,
        // The card has no column headers, so the two triage fields carry their own word.
        riskScore: `Risk ${a.riskScore ?? NOT_RECORDED}`,
        assignee: a.assignee || UNASSIGNED,
        createdAt: formatDateTime(a.createdAt),
    };
}

/**
 * One history row, ready to be laid out.
 *
 * `alertedIban` is the account the alert was raised on, which the block of facts above this table
 * names. Compared by value and not normalised: both strings come out of the same column of the
 * same response, so they agree in case and spacing by construction.
 */
function historyCells(h: HistoryItem, alertedIban: string | null): HistoryRowCells<ReactNode> {
    const alerted = alertedIban !== null && h.fromIban === alertedIban;
    const boundary = bankBoundaryMark(h.toIbanInBank);
    const feeLine = formatFeeLine(h.fee);
    // The empty string and not the table's dash: the second line is drawn only where there is
    // one, so an unsettled payment loses a line rather than gaining a gap. Most of a fraud
    // desk's rows are unsettled, and a column of dashes under every timestamp says nothing.
    const settledLine = h.settledAt ? formatDateTime(h.settledAt) : '';

    return {
        id: formatTransferId(h.id),
        // When the payment was asked for, and under it when the money actually moved. Two
        // timestamps and two questions, read against each other in one cell for the reason the
        // fee is read under the amount: a second date column with nothing beside it saying which
        // question it answers is a column a reader has to decode on every row.
        //
        // Which line is which is said once, in the heading, and the muted weight of the second is
        // what carries it down the rows. The word used to be printed in front of every settled
        // date, which captions one value as many times as the table has rows.
        createdAt: (
            <>
                <span className="created-value">{formatDateTime(h.createdAt)}</span>
                {settledLine && (
                    <span className="created-settled">
                        <span className="visually-hidden">
                            {FIELD_LABEL[HISTORY_SETTLED_FIELD]}:{' '}
                        </span>
                        {settledLine}
                    </span>
                )}
            </>
        ),
        settledAt: settledLine,
        // The amount the customer sent, and under it what the bank added to it. Two lines of one
        // sum, stacked like the two account numbers next door, so the column can be read down.
        // Every row has the second line: on a payment the desk stopped it is the price rather than
        // a charge, and the status beside it is what says so.
        amount: (
            <>
                <span className="amount-value">{formatMoney(h.amount)}</span>
                {feeLine && (
                    <span className="amount-fee">
                        <span className="visually-hidden">{FIELD_LABEL.fee}: </span>
                        {feeLine}
                    </span>
                )}
            </>
        ),
        fee: feeLine,
        status: (
            <span className={`tone-${transferStatusTone(h.status)}`}>
                {transferStatusLabel(h.status, 'analyst')}
            </span>
        ),
        // Source above beneficiary: the money reads down the cell, and the number the eye is
        // hunting sits on the strong line. The word after the beneficiary appears only where the
        // money never left the bank, which is the row this desk can still do something about.
        route: (
            <>
                <span className={alerted ? 'route-from route-from--alerted' : 'route-from'}>
                    {alerted && <span className="visually-hidden">Alerted account: </span>}
                    {formatIban(h.fromIban)}
                </span>
                <span className="route-to">
                    {formatIban(h.toIban)}
                    {boundary && <> <span className="route-boundary">{boundary}</span></>}
                </span>
            </>
        ),
        // The payer's own reference, exactly as it was typed: nothing here interprets it, and an
        // empty box and an untouched one both come out as nothing to draw.
        message: h.message ?? '',
        declineReason: describeDeclineReason(h.declineReason),
    };
}

/**
 * One journal entry, ready to be laid out.
 *
 * The author is never printed as a blank. One entry per alert can carry no name, the one the
 * journal was seeded with from the single notes column that preceded it, and an empty cell in a
 * column of logins reads as a name withheld rather than as one that was never kept. The word for
 * it is the glossary's, so both platforms stand in the same thing.
 *
 * The text is marked, unlike the two stamps: it is the row, and the facts beside it qualify it.
 */
function noteCells(n: AlertNote): AlertNoteRowCells<ReactNode> {
    return {
        writtenAt: formatDateTime(n.writtenAt),
        author: noteAuthorLabel(n.author),
        text: <span className="note-text">{n.text}</span>,
    };
}

/**
 * The locale an ambiguous amount is read in.
 *
 * Only `1,234` needs it: the one string that is a valid number under both the Czech and the
 * English convention and means two different things under them. It is read here rather than in
 * the parser because the shared modules are pure, so that their rules can be tested without a
 * browser, and this is the one thing in the reading that only a browser knows.
 */
function readerLocale(): string {
    return navigator.language || 'cs-CZ';
}

export default function FraudDesk(props: {
    /**
     * The login, which is what the assignment column holds and what the Mine filter matches on.
     *
     * Kept apart from the name below on purpose. The server writes a login into `assignee`, so a
     * comparison against a person's name would quietly stop finding this analyst's own alerts the
     * day the bank starts filling in names.
     */
    username: string;
    /**
     * The person, for the one place on this window that addresses them rather than identifies them.
     *
     * Resolved by the shell, which is the only part of this application that knows a session from
     * a name, and handed down already reduced to a string so the desk has nothing to decide.
     */
    signedInAs: string;
    /**
     * Who this person is to the bank, which the header prints beside their login.
     *
     * Handed down by the shell rather than read here, because the shell is what decided this
     * screen was the right one for the role. The customer application has printed both halves in
     * its header all along and this window printed the login alone, so the same analyst was named
     * two different ways in the same product.
     */
    role: NavRole;
    /**
     * Which alert the address bar is pointing at, which is the one this desk shows.
     *
     * The address bar is the shell's to read, so the selection arrives here as a prop rather than
     * being kept twice. The desk publishes back into the address when it moves the selection, and
     * hears the result on this prop at the next hashchange: `#/alerts/12` is then an alert one
     * analyst can send another, and a reload lands on the case that was open rather than on an
     * empty panel.
     */
    selectedId: number | null;
    onLogout: () => void;
}) {
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
    /**
     * The page that arrived, kept whole.
     *
     * The next request is counted from it and not from the rows on screen: those two stop
     * agreeing the moment an alert raised mid-session pushes a row from one page onto the next
     * and the repeat is dropped. It also carries the total the count line prints.
     */
    const [lastPage, setLastPage] = useState<Page<AlertQueueItem> | null>(null);

    /**
     * How many alerts the payment-status exclusion is keeping off the list right now.
     *
     * Answered by a route of its own rather than worked out from the two totals on screen: the
     * counters count the whole queue before any filter and the page counts what the filters
     * matched, so subtracting them would name every alert the state filter is also excluding and
     * blame the checkbox for it.
     */
    const [hiddenCount, setHiddenCount] = useState(0);
    const [hiddenErr, setHiddenErr] = useState<string | null>(null);

    /**
     * The alert this panel is open on, seeded from the address and moved through it.
     *
     * Not derived from the prop on every render, and the reason is the one correction that is
     * made without a hashchange: when the decided alert leaves the queue the desk replaces the
     * address rather than pushing onto it, and history.replaceState notifies nobody, so the shell
     * is still holding the id that was there. This state is what the panel is drawn from, the
     * prop is what the address says, and the effect below is where the two meet.
     */
    const [selectedId, setSelectedId] = useState<number | null>(props.selectedId);
    const [detail, setDetail] = useState<AlertDetail | null>(null);

    /**
     * Which alert the panel is open on, readable from inside a request that has already left.
     *
     * There was no such thing in any of the three trees, and every call that fills the panel is
     * an await followed by a bare setState. The panel makes two requests about one alert, both of
     * them slower than a click, so an analyst working down the tray was one late answer away from
     * reading the previous alert's payment under this alert's case number, with nothing on either
     * to say they did not belong together. Two more calls land in the same panel from a press,
     * the decision and the assignment, and they can land after the analyst has moved on too.
     *
     * A ref and not the selectedId state, because that is the point: a request holds the value it
     * was sent with, and this holds the value as it stands when the answer arrives. Every landing
     * below compares the two and drops what no longer belongs, the busy flag included: a stale
     * answer that clears the flag leaves the panel showing neither a case nor a wait.
     */
    const openAlert = useRef<number | null>(null);

    /**
     * The payments beside the alert, a page at a time, and what the route said if it refused.
     *
     * Read from the history route and not from `detail.history`, which carries ten rows and no
     * total: a panel that offers more has to know how many there are, and turning its page must
     * not make the server rebuild the alert, the payment, the account and the customer to answer
     * a question about none of them.
     */
    const [history, setHistory] = useState<HistoryPage | null>(null);
    const [historyErr, setHistoryErr] = useState<HistoryProblem | null>(null);

    /**
     * What was typed into each amount box, and what is wrong with it.
     *
     * Two states rather than one because they are two different things: the analyst sees what
     * they wrote, in whatever convention they wrote it, and the query carries the plain decimal
     * the server parses. The refusal is kept per box, because fixing the upper bound must not
     * silently forgive the lower one, and because it names what is wrong with that string rather
     * than only that something is.
     */
    const [amountText, setAmountText] = useState({ min: '', max: '' });
    const [amountReason, setAmountReason] = useState<Record<AmountBound, string | null>>({
        min: null,
        max: null,
    });

    // Four failures rather than four strings: the shared table answers with a statement of what
    // happened, the thing to do about it where there is one, the short reference for the small
    // type, and, on a read, the loader that would ask again. Rendering them is ErrorBox's job.
    const [listErr, setListErr] = useState<ApiFailure | null>(null);
    const [detailErr, setDetailErr] = useState<ApiFailure | null>(null);
    const [decisionErr, setDecisionErr] = useState<ApiFailure | null>(null);
    // Kept apart from the decision's own refusal: a lost race on the assignment costs the analyst
    // nothing they typed, and the sentence for it says so. Sharing one box would put a warning
    // about discarded work under a press that had none at stake.
    const [assignErr, setAssignErr] = useState<ApiFailure | null>(null);
    // This desk had no success state at all: after a decision the alert left the NEW-filtered
    // queue, the panel unmounted with it, and the analyst was left with an empty pane and no
    // statement of what had happened.
    const [decisionMsg, setDecisionMsg] = useState<string | null>(null);
    /*
     * Busy from the first render, before the effect below has asked for anything.
     *
     * The tray prints one sentence when it holds no cards, and with a flag that starts false that
     * sentence was "No alerts": the desk stated the queue was empty on the frame before it had
     * asked, every time it opened. A list is not empty until somebody has answered that it is.
     */
    const [busyList, setBusyList] = useState(true);
    const [busyMore, setBusyMore] = useState(false);
    const [busyDetail, setBusyDetail] = useState(false);
    const [busyHistory, setBusyHistory] = useState(false);
    const [busyHistoryMore, setBusyHistoryMore] = useState(false);
    /**
     * WHICH of the three presses is in flight, and not merely that one is.
     *
     * A flag was enough while no button said anything, and no button said anything: three live
     * looking controls stood over a request that had already left. The word goes on the button
     * that was pressed and on no other, so this has to be the decision rather than a boolean. All
     * three are still disabled together, because the server takes one verdict per alert and the
     * second press is a refusal that would take the typed notes with it.
     */
    const [pendingDecision, setPendingDecision] = useState<FraudDecision | null>(null);
    const busyDecision = pendingDecision !== null;
    const [busyAssign, setBusyAssign] = useState(false);

    const [decisionComment, setDecisionComment] = useState('');
    /**
     * Whether a refusal is refusable yet, read in the three places that need it.
     *
     * Trimmed, because a box holding a space is an empty box: the server trims it too and would
     * store the refusal with a blank reason on it. The button, the sentence under it and the
     * guard in the handler all read this one expression, so the rule cannot be tightened in one
     * of them and left loose in the other two.
     */
    const declineNeedsComment = decisionComment.trim() === '';
    /**
     * The one entry about to be appended, and nothing that is already on the alert.
     *
     * Two states used to stand here, the notes as loaded and the notes as edited, and both are
     * gone with the blob they belonged to. The box no longer opens holding what a colleague wrote,
     * because it can no longer overwrite it: what is typed is appended and what is stored is read
     * a foot above in the journal. So there is nothing to compare against and nothing to echo
     * back, and an empty box is simply a press that adds no entry.
     */
    const [noteText, setNoteText] = useState('');

    /**
     * Whether the middle of the decision block is folded away, and it outlives the selection.
     *
     * Neither openDetail nor closeDetail touches it, unlike the two boxes above, and the reason is
     * what each of them is. What is typed in those boxes is filed against one customer, so it is
     * cleared with the case; the fold is a posture, and a control that undoes itself on every card
     * in a page of twenty-five is not a control. The cost, an analyst forgetting they folded it, is
     * answered by what stays drawn either way: the plate is the only blue object in that half of the
     * pane, Decline is still dead while the comment is empty, and the line that says why is still
     * printed under the buttons.
     *
     * Held here rather than in the address, because it is a habit of the desk and not a place in
     * the bank. The panel does not unmount when the selection moves, so it simply persists; a
     * reload signs the analyst out, which settles the other half of the question.
     */
    const [decisionFolded, setDecisionFolded] = useState(false);

    /**
     * Whether the navigation rail is folded to its handle, and it outlives the selection for the
     * same reason the decision fold does: it is a posture the analyst takes towards the window,
     * not a property of the case in front of them. An analyst who has shut the rail to widen the
     * queue has not asked for it back on the next alert.
     */
    const [navFolded, setNavFolded] = useState(false);

    /*
     * The history that belongs to the alert on screen, and nothing else.
     *
     * Matched here rather than at each of the three places that read it, so a page fetched for
     * one alert can never be drawn under another's facts: the panel makes two requests and this
     * is the slower of them, and a payment row carries nothing that would say whose it is.
     */
    const shownHistory = detail && history && history.alertId === detail.alert.id ? history : null;
    const shownHistoryErr = detail && historyErr && historyErr.alertId === detail.alert.id
        ? historyErr.failure
        : null;

    // amountReason belongs in here beside filters. Typing something that cannot be read into an
    // already empty field leaves the filters untouched, so on filters alone nothing would re-run
    // and the analyst would be told nothing at all.
    useEffect(() => { void reloadList(); }, [filters, amountReason]);

    /*
     * The address, when it says something other than what is on screen.
     *
     * Three things can move it without this desk pressing anything: the window opening on
     * `#/alerts/12`, the Back button, and an id typed into the bar. All three land here, and the
     * comparison is against openAlert rather than against the state beside it, because openAlert
     * is what the requests in flight are checked against: an alert already being fetched must not
     * be fetched a second time by the hashchange that a click of our own produced.
     */
    useEffect(() => {
        const wanted = props.selectedId;
        if (wanted === openAlert.current) return;
        if (wanted === null) closeDetail();
        else void openDetail(wanted);
        // The address and nothing else. Both functions are redeclared on every render, so listing
        // them would run this on every keystroke in a filter box; what it does is guarded by
        // openAlert above rather than by the dependency list.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [props.selectedId]);

    async function reloadList(keepSelection = false) {
        // Checked before the request, and the server checks it again. This half exists to name
        // which two numbers are the wrong way round; the server cannot, because no handler
        // echoes an exception message. The server half exists because the endpoint is reachable
        // without this screen.
        //
        // A box the parser refused is answered with the parser's own sentence, which says what
        // is wrong with that string. The shared guard's unreadable branch is the answer for a
        // caller with no sentence of its own, so by the time it runs both bounds are either
        // readable or empty.
        const problem =
            amountReason.min ??
            amountReason.max ??
            amountRangeProblem(filters, { min: false, max: false });
        if (problem) {
            setAlerts([]);
            setLastPage(null);
            // The counters are deliberately NOT cleared, and this line used to clear them. They
            // count the whole queue before any filter, so a filter this desk refused to send
            // cannot have changed them: dropping the strip left the panel with a heading, an
            // error and no bottom to it, and threw away the one figure on screen that still held.
            // No reference and no retry: nothing was sent, so there is no answer to quote, and
            // asking again with the same two boxes would be refused again. What fixes it is the
            // sentence itself, which names the bound to correct.
            setListErr({ lines: [problem], reference: null });
            // The flag starts true, and this branch returns without a request. Left alone it
            // would leave the tray saying it was still loading for as long as the box is wrong.
            setBusyList(false);
            // Nothing was asked, so nothing is being hidden by anything but the mistake in the
            // box, and the sentence about the Withdrawn filter would be an answer to a question
            // the desk never put.
            setHiddenCount(0);
            setHiddenErr(null);
            return;
        }

        try {
            setBusyList(true);
            setListErr(null);
            // From the top, and as wide as what is already on screen: a decision reloads this
            // list, and a queue that collapsed back to its first page every time an alert was
            // decided would lose the rows the analyst had opened out to reach it.
            const wanted = keepSelection ? Math.max(PAGE_SIZE, alerts.length) : PAGE_SIZE;
            const resp = await fetchAlerts(filters, 0, wanted);
            setAlerts(resp.alerts.items);
            setLastPage(resp.alerts);
            setCounters(resp.counters);
            if (!keepSelection && selectedId && !resp.alerts.items.some(x => x.id === selectedId)) {
                // The panel is being closed, so a detail or a history page still in flight for
                // that alert has nothing left to land on.
                closeDetail();
                // The address said `#/alerts/12` and there is no alert 12 in this queue any
                // more, so it is corrected rather than travelled to: replaceRoute writes no
                // history entry, because nobody went anywhere, and fires no hashchange, so this
                // correction cannot come back through the shell as a selection to open.
                replaceRoute(routeFor('fraud-desk', null));
            }
            // Asked with the filters the queue was just asked with, and asked after it: the two
            // answers are the two halves of one filter, so a hidden count taken under a different
            // query would explain a list that is not on the screen.
            void refreshHidden(filters);
        } catch (e) {
            // A read, so the box carries the way to ask again. The same filters, the same width,
            // and the selection is kept: a queue that failed once is the one place on this desk
            // where a second press costs nothing and is the whole of the answer.
            setListErr(describeApiFailure(e, 'alert-queue', {
                retry: () => void reloadList(keepSelection),
            }));
            // Both, and for one reason: the two of them explain the difference between the queue
            // and the list that has just failed to arrive, and a note about a filter under a
            // queue nobody could re-read is one more sentence between the analyst and the box
            // that says what happened.
            setHiddenCount(0);
            setHiddenErr(null);
        } finally {
            setBusyList(false);
        }
    }

    /**
     * How many alerts the payment-status exclusion is keeping off the queue.
     *
     * A page of one, because only the total is read. The rows are already reachable by ticking
     * the box that is hiding them, so a second copy of twenty-five queue entries would be fetched
     * to be thrown away.
     *
     * Excluding nothing is not asked at all: the route answers an empty page for it without
     * touching the store, and the desk knows that answer before it asks.
     */
    async function refreshHidden(applied: AlertFilters) {
        if (!applied.excludeTransferStatus?.length) {
            setHiddenCount(0);
            setHiddenErr(null);
            return;
        }

        try {
            const hidden = await fetchHiddenAlerts(applied, 0, 1);
            setHiddenCount(hidden.total);
            setHiddenErr(null);
        } catch (e) {
            // Said in the hint's own voice and not in an error box. Nobody asked for this list:
            // it is the desk explaining its own filter, and a failure to explain must not read as
            // a failure of the queue standing beside it, which has just answered.
            setHiddenCount(0);
            setHiddenErr(describeApiError(e, 'alert-hidden'));
        }
    }

    /**
     * The next page of the queue, appended under the cards already in the tray.
     *
     * The tray's own "Loading…" hint is not shown for this: that sentence means the first page,
     * and drawing it would blank a queue the analyst is reading. The button says so instead and
     * keeps its place while it does.
     */
    async function loadMore() {
        if (!lastPage) return;

        try {
            setBusyMore(true);
            setListErr(null);
            const resp = await fetchAlerts(filters, nextPage(lastPage), PAGE_SIZE);
            setAlerts(held => appendPage(held, resp.alerts.items));
            setLastPage(resp.alerts);
            setCounters(resp.counters);
        } catch (e) {
            // The retry is this page again and not the whole queue: the rows already in the tray
            // stay where they are, and the box is drawn under them, beside the button that was
            // pressed to get them.
            setListErr(describeApiFailure(e, 'alert-queue', { retry: () => void loadMore() }));
        } finally {
            setBusyMore(false);
        }
    }

    /**
     * The panel closed, and everything that was open on one alert closed with it.
     *
     * The ref goes first for the same reason it is set first below: a detail or a history page
     * still in flight has nothing left to land on, and the guards on those answers read it.
     */
    function closeDetail() {
        openAlert.current = null;
        setSelectedId(null);
        setDetail(null);
        setDecisionComment('');
        setNoteText('');
        setDecisionMsg(null);
        setDecisionErr(null);
        setAssignErr(null);
    }

    async function openDetail(id: number) {
        /*
         * The selection said out loud, before it is acted on.
         *
         * goTo and not replaceRoute: somebody opened this alert, so it is a move of theirs and
         * Back belongs to them. Every way into this function passes through here, the retry on a
         * failed read and the address effect included, and all of them but a fresh click write
         * the address that is already in the bar, which fires nothing.
         */
        goTo(routeFor('fraud-desk', id));
        // Before anything is asked for, so an answer to the previous question can already tell
        // that it is late by the time it arrives.
        openAlert.current = id;
        setSelectedId(id);
        setDetail(null);
        // The two boxes at the foot of the panel, emptied with the case they were typed against.
        // What is in them is not a draft: one is filed as this analyst's conclusion about the
        // verdict and, on a decline, reaches the customer, and the other is appended to this
        // alert's journal under their name. A sentence about one customer left standing under
        // another's case is a press away from being their record.
        setDecisionComment('');
        setNoteText('');
        setDecisionMsg(null);
        setDecisionErr(null);
        setAssignErr(null);

        // Both requests leave together. Neither answer is needed to build the other, and the
        // history is the slower of the two, so waiting for the alert before asking for it would
        // add a second round trip to the wait before any of it is on screen.
        void loadHistory(id);

        try {
            setBusyDetail(true);
            setDetailErr(null);
            const d = await fetchAlertDetail(id);
            if (openAlert.current !== id) return;
            // The journal rides in here with the alert, so nothing else is fetched for it and
            // nothing is copied into a box: what colleagues wrote is drawn as a record, and the
            // box at the foot of the panel starts empty because it adds rather than replaces.
            setDetail(d);
        } catch (e) {
            if (openAlert.current !== id) return;
            setDetailErr(describeApiFailure(e, 'alert-details', {
                retry: () => void openDetail(id),
            }));
        } finally {
            // Guarded like the rest: a late answer that drops the flag would leave the panel
            // showing neither the case it is fetching nor the fact that it is fetching one.
            if (openAlert.current === id) setBusyDetail(false);
        }
    }

    /** The first page of the customer's payments, and the total this panel could never state. */
    async function loadHistory(id: number) {
        setHistory(null);
        setHistoryErr(null);
        try {
            setBusyHistory(true);
            const page = await fetchAlertHistory(id, 0, HISTORY_PAGE_SIZE);
            if (openAlert.current !== id) return;
            setHistory({ alertId: id, rows: page.items, last: page });
        } catch (e) {
            if (openAlert.current !== id) return;
            setHistoryErr({
                alertId: id,
                failure: describeApiFailure(e, 'alert-history', {
                    retry: () => void loadHistory(id),
                }),
            });
        } finally {
            if (openAlert.current === id) setBusyHistory(false);
        }
    }

    /**
     * The next page of payments, under the rows already in the table.
     *
     * The alert is taken from the page that arrived rather than from the selection, so a press
     * that lands while the analyst is moving on still asks about the alert whose rows it is
     * lengthening, and the answer is filed under that alert or dropped.
     */
    async function loadMoreHistory() {
        if (!history) return;
        const { alertId, last } = history;

        try {
            setBusyHistoryMore(true);
            const page = await fetchAlertHistory(alertId, nextPage(last), HISTORY_PAGE_SIZE);
            setHistory(held => (
                held && held.alertId === alertId
                    ? { alertId, rows: appendPage(held.rows, page.items), last: page }
                    : held
            ));
        } catch (e) {
            setHistoryErr({
                alertId,
                failure: describeApiFailure(e, 'alert-history', {
                    retry: () => void loadMoreHistory(),
                }),
            });
        } finally {
            setBusyHistoryMore(false);
        }
    }

    async function decide(kind: FraudDecision) {
        if (!selectedId) return;
        // The same rule as the disabled button above, and not a repetition of it: a control can be
        // reached by a keyboard, by a press that lands as the box is being emptied, and by
        // anything that calls this function later. The one decision here that cannot be undone is
        // not left resting on a `disabled` attribute. Decline alone: the comment rides with all
        // three verdicts, and only a refusal turns it into what the customer is told.
        if (kind === 'DECLINE' && declineNeedsComment) return;
        // Held for the whole call. Every landing below is checked against the alert the panel is
        // open on now, because a verdict takes longer than a click on the next card and its answer
        // carries the alert, the payment and the sentence that describes both.
        const id = selectedId;
        try {
            setPendingDecision(kind);
            setDecisionErr(null);
            setDecisionMsg(null);
            const updated = await postFraudDecision(id, {
                decision: kind,
                // It rides with all three verdicts and is not the reason for a refusal, which is
                // what the caption over the box now says. Nothing here may narrow it again.
                comment: decisionComment.trim() || undefined,
                // One entry, appended. Nothing is compared and nothing is echoed back: the field
                // used to carry the whole of the alert's notes, so every press filed the box over
                // whatever a colleague had written, and an emptied box erased it. Blank is the
                // same as absent to the server, which is what lets the field ride on every press.
                note: noteText.trim() || undefined,
            });
            if (openAlert.current !== id) return;
            // The answer carries the journal with the entry already in it, so the panel below is
            // current without a second read of the alert.
            setDetail(updated);
            // Both boxes are emptied, and now for one reason rather than two. Each was filed
            // against this alert by the press that just landed, so a line left standing in either
            // would ride again on the next one, and the next press can be a different verdict on a
            // different alert: the desk annotates, the analyst moves on, and what they wrote about
            // this case is filed as the reason that payment was refused.
            setDecisionComment('');
            setNoteText('');
            // Read off the payment the server sent back, not off the button that was pressed: a
            // DECLINE on a payment that has already gone succeeds and records the verdict without
            // stopping anything, so a sentence keyed on the label would say the money was held
            // when it is gone. The sentence itself is the glossary's, and the customer
            // application's desk reads the same one.
            setDecisionMsg(describeDecision(kind, updated.transfer.status));
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
            //
            // Re-read before reporting, because the shared table's sentence for a lost race
            // promises the alert has been reloaded. The queue has a Refresh of its own now, so
            // the promise is no longer the only way an analyst can get a current screen; it is
            // still kept here, since the sentence is about the case they are looking at.
            await reloadList(true);
            try {
                // The alert only. Both boxes keep what the analyst typed, because the write did
                // not happen and this is the one copy of either that is left: nothing was filed,
                // so nothing has been spent.
                const current = await fetchAlertDetail(id);
                if (openAlert.current === id) setDetail(current);
            } catch {
                // The alert may no longer be readable; the message does not depend on it.
            }

            if (openAlert.current !== id) return;
            // The four branches written out here disagreed with the four the customer
            // application's desk wrote for the same four codes, and neither of them had a
            // sentence for ALERT_CHANGED, which is exactly what an analyst gets when their
            // verdict loses a race. One table, twenty codes, both desks.
            //
            // No retry on this box, at the shared table's instruction and for the obvious
            // reason: the press that sends a verdict is the one above it.
            setDecisionErr(describeApiFailure(e, 'alert-decision'));
        } finally {
            setPendingDecision(null);
        }
    }

    /**
     * Takes this alert into the name of whoever is signed in, or gives it back to the queue.
     *
     * Two buttons and not a field, which is the whole shape of assignment here: neither route
     * carries a body, the only name either of them can write is the session's, and there is no
     * directory of analysts in this product to hand an alert to. The filter beside the queue is
     * the other half of the same idea, mine against all.
     *
     * Both are open to every analyst, at the server's insistence: an alert held by somebody who
     * has gone home must not be able to hold up the queue.
     */
    async function changeAssignment(take: boolean) {
        if (!selectedId) return;
        const id = selectedId;

        try {
            setBusyAssign(true);
            setAssignErr(null);
            const updated = take ? await takeAlert(id) : await releaseAlert(id);
            if (openAlert.current !== id) return;
            // Neither box at the foot of the panel is touched by this. An assignment is not a
            // write to the journal and not a verdict, so whatever the analyst has typed since
            // opening the alert is still theirs to send.
            setDetail(updated);
            // The queue prints the name at the foot of every card, so the tray is stale until it
            // is re-read. Keeping the selection, or the panel would unmount under the press.
            await reloadList(true);
        } catch (e) {
            if (openAlert.current !== id) return;
            // No retry, for the same reason the decision has none: the two buttons that do this
            // are in the panel, and a control inside the box would be a third way to write a name.
            setAssignErr(describeApiFailure(e, take ? 'alert-assign' : 'alert-release'));
        } finally {
            setBusyAssign(false);
        }
    }

    function setF<K extends keyof AlertFilters>(k: K, v: string) {
        setFilters(prev => ({ ...prev, [k]: v || undefined }));
    }

    /**
     * Reads one amount bound the way the payment form reads its amount, and files it in both
     * places.
     *
     * A bound that cannot be read is not sent: to the endpoint an unsent parameter and a cleared
     * box look the same, so an analyst who mistyped one would be handed the whole queue dressed
     * up as a filtered one. The refusal is held here and shown instead.
     */
    function changeAmountBound(which: AmountBound, typed: string) {
        setAmountText(prev => ({ ...prev, [which]: typed }));

        const key = which === 'min' ? 'minAmount' : 'maxAmount';
        if (typed.trim() === '') {
            setAmountReason(prev => ({ ...prev, [which]: null }));
            setFilters(prev => ({ ...prev, [key]: undefined }));
            return;
        }

        // The endpoint takes a plain decimal, which is what the parser hands back; the Czech
        // spelling of the same number goes into the box on blur, so the analyst sees which
        // reading they got.
        const parsed = parseAmount(typed, readerLocale());
        setAmountReason(prev => ({ ...prev, [which]: parsed.ok ? null : parsed.reason }));
        setFilters(prev => ({ ...prev, [key]: parsed.ok ? String(parsed.value) : undefined }));
    }

    /** Writes the reading back into the box, so a slip shows itself rather than being guessed at. */
    function normalizeAmountBound(which: AmountBound) {
        const parsed = parseAmount(amountText[which], readerLocale());
        if (parsed.ok) {
            setAmountText(prev => ({ ...prev, [which]: parsed.czech }));
        }
    }

    /*
     * WHICH BOX THE QUEUE WAS REFUSED OVER.
     *
     * The sentence stays in the tray, where the rows would be, and this is the other half of it:
     * the mark that says which of the four filter controls it is about. Without it the panel
     * answers a mistyped bound with a red block a hundred pixels below the box, and nothing at all
     * around the box, so the reader is told what is wrong and left to work out where.
     *
     * The precedence is reloadList's, deliberately and not by accident: a box the parser refused
     * answers for itself first, and the backwards range is a fault of the pair, so it marks both.
     * Written the same way in both places, the mark and the sentence cannot come apart.
     *
     * Cheap to recompute on every render: two strings out of state and one pure function over them.
     */
    const rangeBackwards =
        amountReason.min === null &&
        amountReason.max === null &&
        amountRangeProblem(filters, { min: false, max: false }) !== null;
    const amountInvalid = {
        min: amountReason.min !== null || rangeBackwards,
        max: amountReason.max !== null || rangeBackwards,
    };

    return (
        <div className="shell">
            <div className="window">
                {/*
                  * THE FOUR PARTS OF THE WINDOW, NAMED IN THE MARKUP AS WELL AS IN INK.
                  *
                  * This window printed six block names and had no heading and no landmark of any
                  * kind: every one of them was a div in bold, and a person reading the screen with
                  * anything other than their eyes was handed one undivided sheet. The parts are the
                  * ones a person sees, and there are four: the window's own band, the rail of
                  * screens, the queue of cases, and the case under review. The band and the rail are
                  * furniture around the work, the case is the work, and the queue is how it is
                  * chosen, which is why it is a named region beside the main one rather than inside
                  * it.
                  *
                  * The outline under them runs h1 for the bank at the top of the window, h2 for each
                  * of the two panels, h3 for each box inside the case. No level is skipped and no
                  * level is invented: it is the same shape the customer application's fraud desk
                  * carries, so one analyst reading one product hears one document.
                  */}
                <header className="titlebar">
                    {/*
                      * One product name, qualified by the screen and not by the role: this
                      * window carries the customer screens too once they exist, and the same
                      * screen is named the same way in the customer application.
                      *
                      * The mark stands in front of the name here for the reason it stands in
                      * front of it at this window's own door and in the customer application's
                      * band: one lockup on three surfaces or on none of them. The working
                      * window was the one surface that printed the name as bare text, so the
                      * bank a person had just been shown at the sign-in card disappeared the
                      * moment they were let in.
                      */}
                    <div className="title">
                        <span className="brand">
                            <span className="brand-mark" aria-hidden="true" />
                            {/* The name of the bank is the top of the outline, which is what the
                                customer application's band already makes it. */}
                            <h1 className="brand-name">MiniBank</h1>
                        </span>
                        <span className="title-screen">Fraud Desk</span>
                    </div>
                    <div className="titlebar-right" title={SESSION_IDLE_NOTE}>
                        {/*
                          * Who is at the desk and what they are, which is what the customer
                          * application's header has always printed and this one did not. The
                          * word comes from the same table both applications read: there is no
                          * second role map in this product, and a header that prints
                          * FRAUD_ANALYST or invents its own wording is how there comes to be one.
                          *
                          * The name, where the server knows one, and the login where it does not.
                          * It knows none for an analyst today, so this reads `fraud` still; what
                          * changed is that both applications now ask, so the day a name is
                          * recorded neither window has to be found and fixed.
                          */}
                        <div className="user">{props.signedInAs} · {roleLabel(props.role)}</div>
                        {/* The action paired with "Sign in" is "Sign out". One product, one verb. */}
                        <button className="btn" onClick={props.onLogout}>Sign out</button>
                    </div>
                </header>

                <div className="content content--split">
                    {/*
                      * A third surface, and deliberately not a fourth use of the queue. The panel
                      * beside it is where the cases are; this is where the screens are, and the
                      * window had neither a place that said so nor any landmark at all. The role
                      * comes from the shell, which is what decided this window was the right one
                      * for it, and the screen names itself rather than being worked out from the
                      * address: this window serves one.
                      */}
                    <NavRail
                        role={props.role}
                        current="fraud-desk"
                        folded={navFolded}
                        onToggle={() => setNavFolded(folded => !folded)}
                    />

                    {/* LEFT: queue */}
                    {/* A region and not the main one: this is how a case is chosen, and the case
                        itself is next door. It is named by its own heading rather than by a
                        string written here, so the two can never come apart. */}
                    <section className="left" aria-labelledby={QUEUE_TITLE_ID}>
                        <div className="panel">
                            <h2 className="panel-title" id={QUEUE_TITLE_ID}>{ALERTS_QUEUE_TITLE}</h2>

                            {/*
                              EVERY CONTROL IN HERE CARRIES ITS OWN NAME.

                              The labels used to stand beside their controls and belong to none of
                              them: a bare <label> names nothing, so the whole row of filters was
                              four unnamed boxes to anybody not reading the screen. Each is bound
                              to an id now, except the pair below, where one word cannot name two
                              boxes and the two words are given to the boxes themselves.
                            */}
                            <div className="filters">
                                <div className="row row--2">
                                    <label htmlFor="filter-state">{FIELD_LABEL.state}</label>
                                    {/* The values are the server's, the words are the glossary's,
                                        so the option a person picks reads the same as the state
                                        printed on the cards below. */}
                                    <select id="filter-state" value={filters.state ?? ''} onChange={(e) => setF('state', e.target.value)}>
                                        <option value="">All</option>
                                        <option value="NEW">{alertStateLabel('NEW')}</option>
                                        <option value="SUSPICIOUS">{alertStateLabel('SUSPICIOUS')}</option>
                                        <option value="OK">{alertStateLabel('OK')}</option>
                                    </select>
                                </div>

                                {/*
                                  One field asked about twice, so the row's word is not a label at
                                  all: a <label> over two inputs either names the wrong one of them
                                  or names neither, and this row has space for exactly one word.
                                  The two names go to the boxes, from the shared pair the customer
                                  application prints above its own, so a person reading this screen
                                  sees the short form and a person listening to it hears the same
                                  two words the other platform shows.

                                  Text and not number: these boxes have to accept the string the
                                  queue beside them prints, and a number input reads only the
                                  browser's own convention. Which is what the placeholder now says.
                                  `min` and `max` repeated the label and answered nothing; a comma
                                  in the example says what a number looks like here without a
                                  sentence.
                                */}
                                <div className="row row--3" role="group" aria-label={FIELD_LABEL.amount}>
                                    <span className="row-label">{FIELD_LABEL.amount}</span>
                                    {/*
                                      aria-describedby and not a sentence of their own. The tray
                                      below already carries the one statement about why the queue
                                      is empty, and it is the tray's to carry: printing it a second
                                      time under the box would announce one refusal twice. What the
                                      box owes is to say which sentence is about it, and it can,
                                      because the box down there has a name.
                                    */}
                                    <input
                                        id="filter-amount-min"
                                        inputMode="decimal"
                                        aria-label={AMOUNT_FROM_LABEL}
                                        aria-invalid={amountInvalid.min || undefined}
                                        aria-describedby={amountInvalid.min ? QUEUE_ERROR_ID : undefined}
                                        placeholder={AMOUNT_PLACEHOLDER}
                                        value={amountText.min}
                                        onChange={(e) => changeAmountBound('min', e.target.value)}
                                        onBlur={() => normalizeAmountBound('min')}
                                    />
                                    <input
                                        id="filter-amount-max"
                                        inputMode="decimal"
                                        aria-label={AMOUNT_TO_LABEL}
                                        aria-invalid={amountInvalid.max || undefined}
                                        aria-describedby={amountInvalid.max ? QUEUE_ERROR_ID : undefined}
                                        placeholder={AMOUNT_PLACEHOLDER}
                                        value={amountText.max}
                                        onChange={(e) => changeAmountBound('max', e.target.value)}
                                        onBlur={() => normalizeAmountBound('max')}
                                    />
                                </div>

                                <div className="row row--2">
                                    <label htmlFor="filter-assignee">{FIELD_LABEL.assignee}</label>
                                    {/*
                                      Two positions, not a name to type. The box here was a text
                                      field that could never match anything: no screen could write
                                      the column, so every name typed into it answered an empty
                                      queue. What can be written is one name, the analyst's own,
                                      so what can be filtered on is the same one, and the option
                                      values are this desk's own rather than the server's because
                                      the query the second one builds is a username.
                                    */}
                                    <select
                                        id="filter-assignee"
                                        value={filters.assignee ? 'mine' : ''}
                                        onChange={(e) => {
                                            const mine = e.target.value === 'mine';
                                            setFilters(prev => ({
                                                ...prev,
                                                assignee: mine ? props.username : undefined,
                                            }));
                                        }}
                                    >
                                        <option value="">{ASSIGNED_TO_ANYONE}</option>
                                        <option value="mine">{ASSIGNED_TO_ME}</option>
                                    </select>
                                </div>

                                {/*
                                  One name for one checkbox. There were two: a row label reading
                                  `Withdrawn`, which is a word only this platform used, beside a
                                  lower case restatement of the same instruction. The sentence
                                  under the counters points at this control by name, and it can
                                  only do that if the control has one name.
                                */}
                                <div className="row row--check">
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
                                        {' '}{SHOW_WITHDRAWN_ALERTS}
                                    </label>
                                </div>
                            </div>

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
                                            {/*
                                              WHY THE ALERT WAS RAISED, IN AS MUCH ROOM AS A CARD HAS.

                                              The reason is whatever was written into it, and
                                              nothing shortens it on the way here: the column is
                                              TEXT and the server hands it over whole. A long one
                                              took six lines of a card and pushed the two cards
                                              under it out of the tray, so a queue of twenty-five
                                              became a queue of two, and the card that ate the tray
                                              was not even the one being looked for. Two lines
                                              here, the whole of it under the pointer, and the
                                              whole of it again in the case pane, which is where a
                                              reason is read rather than scanned.
                                            */}
                                            {/* The title takes the field off the record rather
                                                than the cell built from it: a cell is whatever
                                                node the card draws, and an attribute is a
                                                string. Here the two are the same words. */}
                                            <div className="li-bot" title={a.shortReason}>
                                                {cells.shortReason}
                                            </div>
                                            <div className="li-foot">{cells.createdAt} · {cells.assignee}</div>
                                        </button>
                                    );
                                })}
                                {/*
                                  WHAT THE TRAY HAS TO SAY, and it says exactly one thing.

                                  All three statements are in here, where the rows would be,
                                  because all three are about the same thing: what the queue
                                  holds. The error box used to stand above the tray while the
                                  tray, which knows nothing about it, went on saying "No alerts"
                                  underneath, so a refused filter was announced twice and the
                                  second announcement was wrong: nobody had said there were no
                                  alerts, only that this desk had not been able to ask.

                                  Nothing is said at all while a re-read runs under rows that are
                                  already on screen. Those rows are the queue as it last answered,
                                  the wait belongs to the control that started it, and blanking a
                                  tray an analyst is reading to print "Loading" in the middle of it
                                  is how a refresh loses somebody their place.
                                */}
                                {listErr
                                    ? <ErrorBox failure={listErr} id={QUEUE_ERROR_ID} />
                                    : alerts.length === 0 && (
                                        <div className="hint">
                                            {busyList ? QUEUE_LOADING : emptyQueueNote(filters)}
                                        </div>
                                    )}

                                {/*
                                  The last item of the tray, so the way to lengthen the queue is
                                  where the queue runs out. Removed rather than disabled once
                                  everything is on screen: a control that can do nothing still
                                  invites the press that proves it, and the count line under the
                                  tray says so in words instead.
                                */}
                                {hasMore(alerts.length, lastPage?.total ?? 0) && (
                                    <button
                                        type="button"
                                        className="btn list-more"
                                        onClick={() => void loadMore()}
                                        disabled={busyMore}
                                        aria-busy={busyMore || undefined}
                                    >
                                        {busyMore ? SHOW_MORE_BUSY : SHOW_MORE}
                                    </button>
                                )}
                            </div>

                            {/*
                              THE FOOT OF THE QUEUE, and it is drawn at every moment.

                              What is on screen, and the way to ask again. The count says how much
                              of the FILTERED list the tray is holding, so it stands directly under
                              the tray rather than as a fifth cell of the strip below, where it was
                              the one number on a different basis from the other four and nothing
                              said so. It takes the shared string verbatim, capital S and no full
                              stop, so it reads like the same line at the foot of the customer
                              application's lists.

                              It is not printed over an empty tray: the sentence in the tray has
                              just said the same thing in words, and "Showing: 0" under it is that
                              statement a second time. It is not printed with no page either, which
                              is the state after a refusal: there is nothing to count.

                              The button is what this screen never had. This is the one screen in
                              the product whose contents change on their own, the alerts arrive
                              from the server, and the only ways to see them were to disturb a
                              filter or to reload the window and lose the case under review. It
                              keeps the selection for exactly that reason, and it is beside the
                              counters because they go stale together.
                            */}
                            <div className="queue-line">
                                {lastPage && alerts.length > 0 && (
                                    <div className="hint">
                                        {showingLine(alerts.length, lastPage.total)}
                                        {/* Which clock every time on this screen is told by, on
                                            the line that already carries what is on screen, as it
                                            is on the customer application's lists. It was a line
                                            of its own under the strip, which spent a whole row on
                                            a fact that never changes. */}
                                        <span className="meta-sep">{TIMES_ZONE_NOTE}</span>
                                    </div>
                                )}
                                <button
                                    type="button"
                                    className="btn"
                                    onClick={() => void reloadList(true)}
                                    disabled={busyList}
                                    aria-busy={busyList || undefined}
                                >
                                    {busyList ? REFRESH_BUSY : REFRESH}
                                </button>
                            </div>

                            {/*
                              The three numbers count the WHOLE queue, not the list above them,
                              and the server means it that way: it counts before applying any
                              filter. New 7 sitting over a list of three reads as a contradiction
                              until the line says what it is counting.

                              Counting the visible list instead would be worse: the desk opens
                              filtered to new alerts, so two of the three states would be
                              permanently zero, and watching confirmed fraud rise as you work is
                              the whole point of having them.

                              These four count the WHOLE queue, not the list above them, and
                              the server means it that way: it counts before applying any
                              filter. That is the right design and the numbers were never
                              wrong - what was missing was the word now in the first cell. New: 7
                              sitting over a list of three reads as a contradiction until the
                              strip says what it is counting.

                              Counting the visible list instead would be worse: the desk opens
                              filtered to new alerts, so two of the three states would be
                              permanently zero, and watching confirmed fraud rise as you work is
                              the whole point of having them.

                              The three states are the only three, so their sum is the queue.
                            */}
                            {counters && (
                                <div className="counters">
                                    <div>{QUEUE_COUNTERS_BASIS_NARROW}, {queueCounterTotal(counters)}:</div>
                                    {queueCounterCells(counters).map(cell => (
                                        <div key={cell.state}>{cell.label}: {cell.count}</div>
                                    ))}
                                </div>
                            )}

                            {/*
                              The one sentence that reconciles the strip above with the tray above
                              it. Under both, because it is about the difference between them, and
                              printed only when there is a difference to explain: an exclusion that
                              is hiding nothing has nothing to say, and a note that is always on
                              screen has stopped being read by the time it matters.

                              A hint and not an error box in either branch. The count is an
                              explanation of the desk's own default, and the second line is that
                              explanation failing to arrive, which is not a failure of the queue
                              that has just answered beside it.
                            */}
                            {hiddenCount > 0 && (
                                <div className="hint queue-note">{hiddenAlertsNote(hiddenCount)}</div>
                            )}
                            {hiddenErr && <div className="hint queue-note">{hiddenErr}</div>}
                        </div>
                    </section>

                    {/* RIGHT: detail */}
                    {/* The case under review is what this window is for, so it is the main region
                        and everything else on the screen is around it. */}
                    <main className="right">
                        <div className="panel">
                            {/*
                              A name and not a slogan. This title used to tell an analyst who had
                              opened the fraud desk what the fraud desk is for, in the largest type
                              on this half of the screen, on every alert they read.
                            */}
                            <h2 className="panel-title">{ALERT_DETAILS_TITLE}</h2>

                            <div className="panel-scroll">

                                {/*
                                  One statement here too, and the invitation is the last of them.
                                  It used to be drawn on `!selected`, which is not the same
                                  question: the selected id survives a decision and the CARD does
                                  not, since a decided alert leaves the queue the default filter
                                  asks for. So an analyst who had just recorded fraud was invited
                                  to select an alert directly above the alert they had selected,
                                  the whole case still on the pane under the sentence.
                                */}
                                {/* The invitation names the queue and not a position: `on the
                                    left` describes furniture, and stops being true at the width
                                    where the two panels stack. */}
                                {!detail && !busyDetail && !detailErr && (
                                    <div className="hint">{SELECT_ALERT}</div>
                                )}

                                {detailErr && <ErrorBox failure={detailErr} />}
                                {busyDetail && <div className="hint">{ALERT_DETAIL_LOADING}</div>}

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
                                                <div>
                                                    <div className="figure-label">{FIELD_LABEL.amount}</div>
                                                    <div className="figure-value">{formatMoney(detail.transfer.amount)}</div>
                                                </div>
                                                <div>
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

                                        <div className="box">
                                            <h3 className="box-title">Facts</h3>
                                            {/*
                                              Two definition lists and not one, split by subject:
                                              the payment on the left, the case on the right. One
                                              list flowed into two columns would interleave the
                                              pairs and put From beside From balance, which is the
                                              pairing an earlier pass deliberately took apart. Two
                                              lists also put the split where a reader will find it,
                                              in the markup, rather than in an nth-child rule.
                                              The wrapper folds back to one column on its own when
                                              the pane is narrow or the text is large.

                                              The two timestamps are named after the objects they
                                              belong to. They used to be "Created" in one block and
                                              "Time" in another, print identically in the demo data,
                                              and neither word said which of the two it meant.
                                            */}
                                            <div className="facts-split">
                                                <dl className="facts">
                                                    {/* The words are the shared panel map's, not
                                                        this pane's own. They were literals here
                                                        and read the same, which is the state a
                                                        word is in just before it drifts: one
                                                        payment listed in two applications has to
                                                        be listed under one set of names. */}
                                                    <dt>{TRANSFER_DETAIL_LABEL.fromIban}</dt>
                                                    <dd>{formatIban(detail.transfer.fromIban)}</dd>
                                                    {/* The account number and the balance behind it
                                                        are two facts; they used to share one line. */}
                                                    <dt>{TRANSFER_DETAIL_LABEL.fromBalance}</dt>
                                                    <dd className="num">{formatMoney(detail.transfer.fromBalance)}</dd>
                                                    {/* Its own word, not the history column's: this
                                                        pair is the alerted payment's beneficiary,
                                                        and the column below names a route across
                                                        two accounts. */}
                                                    <dt>{TRANSFER_DETAIL_LABEL.toIban}</dt>
                                                    {/* A list of facts one to a line is not
                                                        scanned the way a column is, so here both
                                                        readings are worth printing and the one
                                                        the column leaves unsaid gets said. It is
                                                        a line under the number and not a
                                                        parenthesis after it: this list already
                                                        refuses to put two things on one line, and
                                                        the second line is a div so it stands on
                                                        its own with no stylesheet at all. */}
                                                    <dd>
                                                        {formatIban(detail.transfer.toIban)}
                                                        <div className="fact-note">
                                                            {bankBoundaryLabel(
                                                                detail.transfer.toIbanInBank,
                                                            )}
                                                        </div>
                                                    </dd>
                                                    <dt>{TRANSFER_DETAIL_LABEL.fee}</dt>
                                                    <dd className="num">{formatMoney(detail.transfer.feeAmount)}</dd>
                                                    {/*
                                                      What is left to happen to money that has
                                                      already left the account, which is a fact the
                                                      wire carries and neither desk was printing.

                                                      Drawn only where there is something to say.
                                                      The field is null on a payment credited
                                                      inside this bank, on anything unsettled, and
                                                      on every row written before the column
                                                      existed, so an empty line here would read as
                                                      "the money stayed with us" on three
                                                      situations, one of which is most of a fraud
                                                      desk's queue. Which side of the bank the
                                                      money went is the To line above and nothing
                                                      else.
                                                    */}
                                                    {dispatchStateLabel(detail.transfer.dispatchState) && (
                                                        <>
                                                            <dt>{TRANSFER_DETAIL_LABEL.dispatchState}</dt>
                                                            <dd>
                                                                {dispatchStateLabel(
                                                                    detail.transfer.dispatchState,
                                                                )}
                                                            </dd>
                                                        </>
                                                    )}
                                                    <dt>{TRANSFER_DETAIL_LABEL.authMethod}</dt>
                                                    <dd>{authMethodLabel(detail.transfer.authMethod) || NOT_RECORDED}</dd>
                                                    {/*
                                                      WHAT THE PAYER SAID THEY WERE PAYING FOR,
                                                      which is the one thing about the alerted
                                                      payment this pane could not show.

                                                      The customer types it on the form, the form
                                                      counts it against its limit and the bank
                                                      stores it, and the analyst reviewing that
                                                      very payment read every fact about it except
                                                      the payer's own account of it. It is the first
                                                      of the two rows of prose this column ends on,
                                                      which is the order the shared field list reads
                                                      the payment in.

                                                      Drawn only where there is text. Null means
                                                      the box was left alone, and a row reading
                                                      Message for recipient with nothing after it
                                                      says something was typed and lost.
                                                    */}
                                                    {detail.transfer.message && (
                                                        <>
                                                            <dt>{TRANSFER_DETAIL_LABEL.message}</dt>
                                                            <dd>{detail.transfer.message}</dd>
                                                        </>
                                                    )}
                                                    {/*
                                                      WHY THE BANK STOPPED IT, under the payer's
                                                      own words, because that is the order the two
                                                      sentences happened in and the bank's is the
                                                      last word on a payment.

                                                      This pane could not show it at all: the field
                                                      reached the wire on the customer's record and
                                                      on every history row, including the rows drawn
                                                      a few hundred lines below this one, and not on
                                                      the alerted payment. So an analyst could read
                                                      why any earlier payment had been refused and
                                                      not why the one they were deciding about was.
                                                      On the demonstration data the decided alert
                                                      hangs off a payment the customer withdrew, and
                                                      that sentence is the whole reason the case
                                                      closed without the bank stopping anything.

                                                      Read through the shared sentence rather than
                                                      printed raw, which is what the history rows do
                                                      with the same field, so one string does not
                                                      read two ways on one screen. It is the guard
                                                      as well: the helper answers the empty string
                                                      for a payment that has not been refused, so
                                                      nothing is drawn where there is nothing to
                                                      say.

                                                      Captioned Decline reason and never as the
                                                      reason for a decision. This is the bank's
                                                      sentence about the payment; what a person
                                                      concluded is the comment further down, and
                                                      the two were one field once already.
                                                    */}
                                                    {describeDeclineReason(detail.transfer.declineReason) && (
                                                        <>
                                                            <dt>{TRANSFER_DETAIL_LABEL.declineReason}</dt>
                                                            <dd>{describeDeclineReason(detail.transfer.declineReason)}</dd>
                                                        </>
                                                    )}
                                                </dl>
                                                <dl className="facts">
                                                    <dt>Payment created</dt>
                                                    <dd>{formatDateTime(detail.transfer.createdAt)}</dd>
                                                    <dt>Alert raised</dt>
                                                    <dd>{formatDateTime(detail.alert.createdAt)}</dd>
                                                    <dt>{FIELD_LABEL.assignee}</dt>
                                                    <dd>{detail.alert.assignee || UNASSIGNED}</dd>
                                                    {/*
                                                      The bank's own sentence about the payment,
                                                      under a word that says so.

                                                      `Reason` was enough while this line was the
                                                      only prose on the pane, and it stopped being
                                                      enough the moment the analyst's comment got a
                                                      line of its own beneath it: two blocks of
                                                      text, one from the rules and one from a
                                                      colleague, and the shorter word over the
                                                      first would read as the general case of the
                                                      second. The queue's column keeps `Reason`,
                                                      where there is nothing to confuse it with.

                                                      What used to make this line dishonest is gone
                                                      from the server as well: the analyst's words
                                                      were appended into this very field, so one
                                                      line carried the bank's suspicion and a
                                                      person's conclusion with nothing between them.
                                                    */}
                                                    <dt>{ALERT_REASON_LABEL}</dt>
                                                    <dd>{detail.alert.reason}</dd>
                                                    {/*
                                                      THE VERDICT OF RECORD, and the two facts that
                                                      make it one: who took it and when.

                                                      Drawn only on an alert that carries one. All
                                                      three are null together until a decision is
                                                      taken, and "Decision: not recorded" on every
                                                      new alert would be three lines saying what the
                                                      alert state at the head of the pane has
                                                      already said. Once there is a verdict it is
                                                      the most important thing on the pane after
                                                      the money, and the pane used to be silent
                                                      about it: an analyst opening a decided alert
                                                      read the queue's own word for the state and
                                                      could not find out who had decided it.

                                                      The analyst's name is the one field of the
                                                      three that can be missing on its own. A
                                                      decision taken from the console has no login
                                                      behind it, so the row says nothing was
                                                      recorded rather than leaving a gap that reads
                                                      as a value withheld.
                                                    */}
                                                    {detail.alert.decision && (
                                                        <>
                                                            <dt>Decision</dt>
                                                            <dd>{decisionLabel(detail.alert.decision)}</dd>
                                                            <dt>Decided by</dt>
                                                            <dd>{detail.alert.decidedBy || NOT_RECORDED}</dd>
                                                            <dt>Resolved</dt>
                                                            <dd>{formatDateTime(detail.alert.resolvedAt)}</dd>
                                                        </>
                                                    )}
                                                    {/*
                                                      WHAT THE ANALYST CONCLUDED, on its own line
                                                      and on any of the three verdicts.

                                                      It is not part of the block above it and does
                                                      not share that block's condition. A comment
                                                      can be filed with an alert that has no
                                                      decision on it at all, which is what the third
                                                      button does, and the alert's own reason two
                                                      rows up is the bank's sentence rather than
                                                      this one: it used to be appended there, and
                                                      then a person's conclusion inherited the
                                                      authority of the rules'.

                                                      Drawn only where somebody wrote one. Null
                                                      covers an open alert and a verdict taken
                                                      without a word, and neither is worth a line
                                                      saying nothing was recorded: the state at the
                                                      head of the pane has already said the first,
                                                      and the second is a fact about a box, not
                                                      about a case.

                                                      A later decision REPLACES it, which is the
                                                      whole difference between this line and the
                                                      journal below the history, and the reason
                                                      both exist.
                                                    */}
                                                    {detail.alert.decisionComment && (
                                                        <>
                                                            <dt>{DECISION_COMMENT_LABEL}</dt>
                                                            <dd>{detail.alert.decisionComment}</dd>
                                                        </>
                                                    )}
                                                </dl>
                                            </div>

                                            {/*
                                              Who holds the case, which is the one fact in the list
                                              above that this screen can change.

                                              Two buttons rather than a field, because the only
                                              name either route can write is the session's: there
                                              is no directory of analysts here and nobody to hand
                                              an alert to. Both are drawn at every moment and
                                              disabled where they would do nothing, unlike the
                                              queue's own Show more, which is removed: this is a
                                              fixed pair in a panel read forty times a day, and a
                                              control that appears and disappears under the pointer
                                              costs more than one that greys.

                                              Taking an alert a colleague holds is allowed, at the
                                              server's insistence, so Take is refused only when the
                                              alert is already in this analyst's name.
                                            */}
                                            <div className="actions">
                                                <button
                                                    type="button"
                                                    className="btn"
                                                    disabled={
                                                        busyAssign
                                                        || detail.alert.assignee === props.username
                                                    }
                                                    aria-busy={busyAssign || undefined}
                                                    onClick={() => void changeAssignment(true)}
                                                >{TAKE_ALERT}</button>
                                                <button
                                                    type="button"
                                                    className="btn"
                                                    disabled={busyAssign || !detail.alert.assignee}
                                                    aria-busy={busyAssign || undefined}
                                                    onClick={() => void changeAssignment(false)}
                                                >{RELEASE_ALERT}</button>
                                            </div>

                                            {assignErr && <ErrorBox failure={assignErr} />}
                                        </div>

                                        {/*
                                          THE CUSTOMER'S PAYMENTS, read from their own route.

                                          `detail.history` is no longer drawn. It carries ten rows
                                          and says nothing about how many there are, so the panel
                                          could offer no more and could not even say what it was
                                          showing ten of; the only mention of the cut was the word
                                          in the title. The route beside it answers a page and a
                                          total, so the foot of the table can state both.

                                          The rows are matched to the alert they were fetched for
                                          before they are drawn. Two requests are now in flight for
                                          one panel and this is the slower one; nothing on a
                                          payment row says whose it is, so a page that arrives
                                          after the analyst has moved on must not be laid under
                                          another customer's facts.
                                        */}
                                        <div className="box box--history">
                                            <h3 className="box-title">{CUSTOMER_HISTORY_TITLE}</h3>
                                            {shownHistoryErr && <ErrorBox failure={shownHistoryErr} />}
                                            {busyHistory && <div className="hint">Loading payments…</div>}
                                            {shownHistory && (shownHistory.rows.length === 0 ? (
                                                <div className="hint">{NO_HISTORY}</div>
                                            ) : (
                                                <table className="history">
                                                    <colgroup>
                                                        {HISTORY_COLUMN_FIELDS.map(f => (
                                                            <col
                                                                key={f}
                                                                className={HISTORY_COLUMN_CLASS[f]?.col}
                                                            />
                                                        ))}
                                                    </colgroup>
                                                    <thead>
                                                        <tr>
                                                            {HISTORY_COLUMN_FIELDS.map(f => (
                                                                <th
                                                                    key={f}
                                                                    scope="col"
                                                                    className={HISTORY_COLUMN_CLASS[f]?.cell}
                                                                >
                                                                    {HISTORY_FIELD_LABEL[f]}
                                                                </th>
                                                            ))}
                                                        </tr>
                                                    </thead>
                                                    {shownHistory.rows.map((h) => {
                                                        // Nothing stands above the first row, so
                                                        // it prints its account; every row after
                                                        // it is compared with the one before.
                                                        const cells = historyCells(
                                                            h,
                                                            detail.transfer.fromIban,
                                                        );
                                                        return (
                                                            /* One row group per payment, so the
                                                               reason a payment was refused stays
                                                               part of the row it explains. */
                                                            <tbody key={h.id}>
                                                                <tr>
                                                                    {HISTORY_COLUMN_FIELDS.map(f => (
                                                                        <td
                                                                            key={f}
                                                                            className={HISTORY_COLUMN_CLASS[f]?.cell}
                                                                        >
                                                                            {cells[f]}
                                                                        </td>
                                                                    ))}
                                                                </tr>
                                                                {/*
                                                                  The two fields that are prose,
                                                                  in the order they were written:
                                                                  the payer's words, then the
                                                                  bank's. Both stand under the row
                                                                  rather than in it, and each is
                                                                  drawn only where there is text,
                                                                  so a payment with nothing to add
                                                                  costs the table no height at all.

                                                                  The list is the shared one, so a
                                                                  third piece of prose on the wire
                                                                  arrives here on the build that
                                                                  adds it rather than on the day
                                                                  somebody notices.
                                                                */}
                                                                {HISTORY_UNDER_ROW_FIELDS.map(f => (
                                                                    cells[f] ? (
                                                                        <tr key={f} className="history-note">
                                                                            <td colSpan={HISTORY_COLUMN_FIELDS.length}>
                                                                                {FIELD_LABEL[f]}:{' '}
                                                                                {cells[f]}
                                                                            </td>
                                                                        </tr>
                                                                    ) : null
                                                                ))}
                                                            </tbody>
                                                        );
                                                    })}
                                                </table>
                                            ))}

                                            {/*
                                              How many of the customer's payments there are, and
                                              the way to see the rest of them.

                                              The count line is the shared one, word for word, so
                                              the foot of this table reads like the foot of the
                                              queue beside it and like the same line in the
                                              customer application. The button is removed rather
                                              than disabled once every payment is on screen, which
                                              is the rule the queue's own Show more follows: there
                                              the count line says in words that the list is whole.
                                            */}
                                            {shownHistory && (
                                                <div className="box-foot">
                                                    <div className="hint">
                                                        {showingLine(
                                                            shownHistory.rows.length,
                                                            shownHistory.last.total,
                                                        )}
                                                    </div>
                                                    {hasMore(
                                                        shownHistory.rows.length,
                                                        shownHistory.last.total,
                                                    ) && (
                                                        <button
                                                            type="button"
                                                            className="btn"
                                                            onClick={() => void loadMoreHistory()}
                                                            disabled={busyHistoryMore}
                                                            aria-busy={busyHistoryMore || undefined}
                                                        >
                                                            {busyHistoryMore ? SHOW_MORE_BUSY : SHOW_MORE}
                                                        </button>
                                                    )}
                                                </div>
                                            )}
                                        </div>

                                        {/*
                                          THE CASE NOTES, which are a record and no longer a field.

                                          What stood here was one line in the list of facts above,
                                          holding a single string that the box at the foot of the
                                          panel loaded, edited and filed back. Two analysts working
                                          one alert overwrote each other and nothing said who had
                                          written what, or when. This is the same material as a
                                          table of entries: append only, oldest first, no edit and
                                          no delete on this screen or on the wire.

                                          It is the last block of the scroller, directly above the
                                          box that adds to it. The order down the pane is the
                                          subject, the facts, the evidence, then the record of what
                                          people have made of them, and a reader arrives at the
                                          journal one line before the control that appends to it.

                                          It carries no count line and no Show more, unlike the
                                          table above it: the journal arrives whole inside the
                                          alert, so there is no page to state and nothing further
                                          to ask for.
                                        */}
                                        <div className="box box--notes">
                                            <h3 className="box-title">{ALERT_NOTES_TITLE}</h3>
                                            {detail.notes.length === 0 ? (
                                                <div className="hint">{NO_ALERT_NOTES}</div>
                                            ) : (
                                                <table className="notes">
                                                    <colgroup>
                                                        {ALERT_NOTE_FIELDS.map(f => (
                                                            <col key={f} className={NOTE_COLUMN_CLASS[f]} />
                                                        ))}
                                                    </colgroup>
                                                    <thead>
                                                        <tr>
                                                            {ALERT_NOTE_FIELDS.map(f => (
                                                                <th key={f} scope="col">
                                                                    {ALERT_NOTE_LABEL[f]}
                                                                </th>
                                                            ))}
                                                        </tr>
                                                    </thead>
                                                    <tbody>
                                                        {/*
                                                          Keyed on the stamp and the position, and
                                                          not on the stamp alone. An entry carries
                                                          no id, the server writes the time it
                                                          received the press, and two presses can
                                                          land inside one clock tick; the index
                                                          alone would be a key that means nothing
                                                          in a list that only ever grows at the end.
                                                        */}
                                                        {detail.notes.map((n, i) => {
                                                            const cells = noteCells(n);
                                                            return (
                                                                <tr key={`${n.writtenAt}-${i}`}>
                                                                    {ALERT_NOTE_FIELDS.map(f => (
                                                                        <td key={f}>{cells[f]}</td>
                                                                    ))}
                                                                </tr>
                                                            );
                                                        })}
                                                    </tbody>
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
                                    {/*
                                      THE GRIP ON THE LINE, and it is the first child because it
                                      sits on the hairline rather than in the column under it: it
                                      is taken out of flow and hung over the top edge of this
                                      block, half above the line and half below.

                                      What it folds is the middle of the block, the title, the two
                                      boxes and the rule about the comment. What it can never fold
                                      is what follows the region: the three buttons and the answer
                                      to a press. A verdict taken from the folded state has to be
                                      able to say what it did.

                                      Not a .btn. That class carries the furniture of a control in
                                      a row of controls, a 34px floor among them, and this is
                                      window furniture, sized in pixels so that it keeps its shape
                                      at any text size.

                                      An arrow and no plate around it. A pill the width of a word
                                      read as a fourth button on a screen whose whole lower half is
                                      buttons; the caret alone reads as what it is, a grip on the
                                      seam between two parts of a pane.

                                      The word is the accessible name and the caret is the picture,
                                      and the caret points where the LINE will travel, not where
                                      the content goes: folded, the seam is low and a press lifts
                                      it, so it points up. Open, the seam is high and a press
                                      brings it down. Pointing it the other way, at the content
                                      rather than at the line it is sitting on, is what made it
                                      read backwards.
                                    */}
                                    <button
                                        type="button"
                                        className="fold-handle"
                                        aria-expanded={!decisionFolded}
                                        aria-controls={DECISION_FOLD_ID}
                                        onClick={() => setDecisionFolded(folded => !folded)}
                                    >
                                        <span aria-hidden="true">{decisionFolded ? '▲' : '▼'}</span>
                                        <span className="visually-hidden">
                                            {decisionFolded ? DECISION_UNFOLD_LABEL : DECISION_FOLD_LABEL}
                                        </span>
                                    </button>

                                    {/*
                                      WHAT FOLDS, and it is hidden rather than unmounted.

                                      `hidden` and not `{!decisionFolded && ...}`. The comment box
                                      holds the sentence a declined customer is shown, and an
                                      analyst who folds the block halfway through writing one must
                                      find it there when they unfold it: taking the region out of
                                      the tree would throw that text away, silently, at the press
                                      of a plate whose whole promise is that nothing is lost.
                                    */}
                                    <div className="decision-fold" id={DECISION_FOLD_ID} hidden={decisionFolded}>
                                        <h3 className="box-title">{DECISION_TITLE}</h3>
                                        {/*
                                          The caption is the shared one, and it says "this decision"
                                          rather than "the reason": the comment rides with all three
                                          presses, so a word about declining would tell an analyst
                                          clearing an alert that what they wrote belongs to a refusal
                                          they are not making. The other desk carries the same string.

                                          A textarea and not a single line box. On a decline this text
                                          becomes the sentence the customer is shown for why their
                                          payment was stopped, so it is prose, and a field that shows
                                          forty characters of it invites forty characters. The
                                          placeholder is an example of what to write rather than the
                                          word `optional`: what happens to an empty box is said once,
                                          in the hint under the buttons, where it covers both boxes.
                                        */}
                                        <div className="row row--2">
                                            <label htmlFor="decision-comment">
                                                {DECISION_COMMENT_LABEL}<span aria-hidden="true"> *</span>
                                            </label>
                                            <textarea
                                                id="decision-comment"
                                                /*
                                                 * Two lines and not the other box's three, which is a
                                                 * height and not a kind: both are prose boxes and the
                                                 * customer application makes both three. This block is
                                                 * pinned as the panel's footer rather than scrolling
                                                 * with the page, so every line it grows is a line taken
                                                 * off the evidence above it, and the comment is one
                                                 * sentence to a customer where an entry is a paragraph
                                                 * for a colleague. Both grow on drag and scroll past
                                                 * their cap.
                                                 */
                                                rows={2}
                                                maxLength={MAX_DECISION_COMMENT}
                                                value={decisionComment}
                                                onChange={(e) => setDecisionComment(e.target.value)}
                                                placeholder={DECISION_COMMENT_PLACEHOLDER}
                                            />
                                            <div className="field-counter">
                                                {decisionComment.length}/{MAX_DECISION_COMMENT}
                                            </div>
                                        </div>
                                        {/*
                                          ONE ENTRY, and an empty box beside the journal rather than a
                                          copy of it.

                                          It used to open holding the whole of the alert's notes, which
                                          is what made it dangerous: whatever was left in it was filed
                                          over what a colleague had written, an emptied box included.
                                          What is stored is read as a record a few lines above, and
                                          what is typed here is added to the end of it under this
                                          analyst's name.

                                          The caption is a verb for that reason, the only one in this
                                          footer. It also still says who reads it, which is the
                                          difference between this box and the one above: the comment
                                          rides with the verdict and its substance reaches the customer
                                          on a refusal, and an entry reaches colleagues and nobody else.
                                        */}
                                        <div className="row row--2">
                                            <label htmlFor="decision-note">{DECISION_NOTE_LABEL}</label>
                                            <textarea
                                                id="decision-note"
                                                rows={3}
                                                maxLength={MAX_DECISION_NOTE}
                                                value={noteText}
                                                onChange={(e) => setNoteText(e.target.value)}
                                                placeholder={DECISION_NOTE_PLACEHOLDER}
                                            />
                                            <div className="field-counter">
                                                {noteText.length}/{MAX_DECISION_NOTE}
                                            </div>
                                        </div>

                                        {/*
                                          Why Decline is dead while the box above it is empty.

                                          Inside the folding region and last in it, so it stands
                                          over the buttons rather than under them and is read on
                                          the way to a press rather than after one. It folds away
                                          with the box it is about, which is the point: the rule
                                          is a condition on something to type, and with nothing on
                                          screen to type into there is nothing for it to explain.
                                          The star on the box's own label says which box.
                                        */}
                                        <div className="hint">* {DECLINE_NEEDS_COMMENT}</div>
                                    </div>

                                    {/* The buttons mirror the domain guards exactly, so
                                        a click the server would refuse - taking the
                                        typed notes down with it - is not reachable.
                                        Approve only from a new alert; Decline from
                                        anything not already recorded as fraud, which is
                                        what lets fraud confirmed after the money left be
                                        recorded on an alert that had already been cleared.

                                        The words are the glossary's. This desk said
                                        "release to customer" and "record fraud" where the
                                        customer application's desk said "release to the
                                        customer" and "record confirmed fraud", so one
                                        analyst doing one job read two labels for the same
                                        press. */}
                                    {/*
                                      The word goes on the button that was pressed and on no
                                      other. Three buttons all reading `Applying…` would say three
                                      decisions were being taken, and a row that said nothing at
                                      all left three live looking controls over a request already
                                      out. All three are disabled and marked busy together,
                                      because the server takes one verdict per alert and a second
                                      press is a refusal that would carry the typed notes with it.
                                    */}
                                    <div className="actions">
                                        <button
                                            className="btn btn--primary"
                                            disabled={busyDecision || detail.alert.state !== 'NEW'}
                                            aria-busy={busyDecision || undefined}
                                            onClick={() => decide('APPROVE')}
                                        >
                                            {pendingDecision === 'APPROVE'
                                                ? DECISION_BUSY
                                                : decisionActionLabel('APPROVE')}
                                        </button>
                                        {/* The third term is the comment box. Declining is the
                                            one press on this desk that reaches the customer and
                                            cannot be taken back, and what it sends them is the
                                            sentence in that box, so an empty box is not a
                                            decision this bank takes. The line under the row says
                                            so while the button is dead. */}
                                        <button
                                            className="btn btn--danger"
                                            disabled={busyDecision || detail.alert.state === 'SUSPICIOUS' || declineNeedsComment}
                                            aria-busy={busyDecision || undefined}
                                            onClick={() => decide('DECLINE')}
                                        >
                                            {pendingDecision === 'DECLINE'
                                                ? DECISION_BUSY
                                                : decisionActionLabel('DECLINE')}
                                        </button>
                                        {/* The token this posts was REQUEST_CONFIRMATION, which
                                            named something it has never done: it asks nobody for
                                            anything and takes no decision. */}
                                        <button
                                            className="btn btn--quiet"
                                            disabled={busyDecision}
                                            aria-busy={busyDecision || undefined}
                                            onClick={() => decide('ANNOTATE')}
                                        >
                                            {pendingDecision === 'ANNOTATE'
                                                ? DECISION_BUSY
                                                : decisionActionLabel('ANNOTATE')}
                                        </button>
                                    </div>

                                    {/* Only against a payment that has already gone, which is
                                        the one case the buttons cannot show for themselves. */}
                                    {detail.transfer.status === 'SENT' && (
                                        <div className="hint">{DECLINE_ALREADY_SENT}</div>
                                    )}

                                    {/*
                                      WHAT THE PRESS DID, after the press that did it.

                                      Both of these used to stand above the buttons, between the
                                      notes box and the row of controls, where a sentence about a
                                      verdict already taken sat in the path of the next one. An
                                      outcome is read after the action, and it is worth a title:
                                      an unlabelled green line under a row of buttons is not
                                      obviously an answer to any of them.
                                    */}
                                    {decisionErr && <ErrorBox failure={decisionErr} />}
                                    {decisionMsg && (
                                        <div className="result">
                                            <div className="result-title">{DECISION_RESULT_TITLE}</div>
                                            {decisionMsg}
                                        </div>
                                    )}
                                </div>
                            )}
                        </div>
                    </main>

                </div>
            </div>
        </div>
    );
}
