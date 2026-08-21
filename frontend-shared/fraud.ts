/**
 * The fraud desk's half of the API: what its endpoints return, and how to call them.
 *
 * Shared because it was written twice. Both front ends carry a fraud desk - the analyst's own
 * application, and the same desk inside the customer application for a signed-in analyst - and
 * each had its own copy of every type below and of all three calls. The copies were maintained
 * in parallel rather than left to rot, which is the expensive kind: retiring one field from the
 * server meant finding it in two places, and one of them could always be missed.
 *
 * The screens stay separate. Each application keeps its own desk, its own look and its own shell;
 * what is shared here is only the shape of the wire and the calls that cross it.
 */

import type { Money } from './money';
import { API_BASE, apiFetch, handle } from './http';
import type { Page } from './paging';
import { applyPaging, DEFAULT_PAGE_SIZE } from './paging';

export interface AlertQueueItem {
    id: number;
    alertCode: string;
    transferCode: string;
    state: string;
    // The transfer's status, not the alert's. Without it a payment that has already gone looks
    // identical in the queue to one still held for review.
    transferStatus: string;
    amount: Money;
    shortReason: string;
    createdAt: string | null;
    riskScore: number | null;
    assignee: string | null;
}

export interface AlertCounters {
    newCount: number;
    suspiciousCount: number;
    okCount: number;
}

/*
 * Two counts, and they answer different questions on purpose. The page inside `alerts` describes
 * what the current filters matched, so the foot of the list can say how much of it is on screen.
 * `counters` counts every alert in every state before any filter and before any page, so the line
 * above the list keeps saying how much work exists while the analyst reads three rows of it.
 */
export interface AlertQueueResponse {
    alerts: Page<AlertQueueItem>;
    counters: AlertCounters;
}

export interface AlertInfo {
    id: number;
    state: string;
    // The analyst's verdict, who recorded it and when. `| null` rather than optional, because
    // the server always sends the keys and null is the meaningful value: an alert nobody has
    // decided is a different thing from a field that is missing. decidedBy is null for a
    // decision taken from the console, which has no login.
    decision: string | null;
    decidedBy: string | null;
    resolvedAt: string | null;
    reason: string;
    riskScore: number | null;
    createdAt: string | null;
    /**
     * Who holds the alert, written by the assignment route and by nothing else.
     *
     * No screen types a name here and no request body carries one: the only name that can be
     * written is the session's, which is the rule decidedBy already followed. See {@link takeAlert}.
     */
    assignee: string | null;
    /**
     * Read only, and the whole of what is left of tags.
     *
     * The decision route no longer accepts them, so nothing in either application can write this
     * column; it stays on the wire because the column is still read back, and an alert tagged by
     * anything else still shows what it carries. Do not build an editor against it. What that cost
     * the last time is in {@link FraudDecisionRequest}.
     */
    tags: string[];
    notes: string | null;
}

export interface TransferInfo {
    id: number;
    code: string;
    status: string;
    fromIban: string;
    fromBalance: Money;
    toIban: string;
    /** Whether this bank holds the account named above. Same fact, same rule, as on HistoryItem. */
    toIbanInBank: boolean;
    amount: Money;
    feeAmount: Money;
    createdAt: string | null;
    authMethod: string | null;
}

/**
 * One earlier payment, in the two lists that read this type: the customer's own history, and the
 * history beside an alert.
 *
 * `fromIban` is not nullable, and the server holds up its end: both places that build the record
 * refuse to emit a row whose source account has no number rather than send a null through. A
 * customer can hold more than one account, so without it neither list can say which of them the
 * money left, and the analyst cannot tell the account the alert was raised on from its neighbour.
 */
export interface HistoryItem {
    id: number;
    createdAt: string | null;
    amount: Money;
    /**
     * What this payment cost: the fee that was taken where it settled, and what the tariff would
     * take where it has not.
     *
     * Never null, which is why it is not written `| null`. The wire used to carry the charge alone
     * and send null for everything unsettled, and the tables then drew a fee line on some rows and
     * none on others - on the desk, on most of them, since held and refused payments are what a
     * desk reads. A column filled here and blank there does not read as two answers; it reads as
     * one that went missing.
     *
     * The two readings are not distinguished by this field and are not meant to be. `status` is
     * next to it in every table, and a fee beside DECLINED is a price rather than a receipt.
     */
    fee: Money;
    status: string;
    fromIban: string;
    toIban: string;
    /**
     * Whether this bank holds the account named above, which is what decides whether the money
     * stayed inside or was owed to the payment network.
     *
     * An answer, not the two halves it is made of. The server has both: a settled payment
     * registers a dispatch obligation exactly when its destination was not ours, so an empty
     * dispatch state on a SENT transfer is the record that the credit happened here, and anything
     * that has not settled has no such record and is answered from the live store instead.
     * Sending the raw dispatch state would put that rule in every desk that reads this row, in the
     * same words, which is the duplication this module and the field list next door exist to end.
     *
     * Never null and never a third value: an IBAN either is one of ours at the moment this row is
     * read, or it is not. Which of the two is worth saying out loud on a row, and in what words,
     * is settled in glossary.ts.
     */
    toIbanInBank: boolean;
    declineReason: string | null;
}

export interface AlertDetail {
    alert: AlertInfo;
    transfer: TransferInfo;
    history: HistoryItem[];
}

/**
 * What an analyst can post about an alert, and the one name each of the three carries.
 *
 * The third was called REQUEST_CONFIRMATION, which named something it has never done: it asks
 * nobody for anything, it saves the notes and it takes no decision. The server accepts both
 * spellings so the rename can reach the two desks in any order, but only ANNOTATE is on this
 * list: the list is what a client may send, and leaving the old name here is what would let a
 * screen go on sending it.
 *
 * This union is also the only declaration of the three tokens in this directory. The word list
 * next door keys its buttons and its outcome sentences off this type instead of writing the set
 * out a second time, because a closed set declared twice is a set that grows in one place: a
 * fourth decision added to the wire and not to the labels compiles, and answers the analyst with
 * the token.
 *
 * Rows written before the rename keep the old spelling in the database forever. Reading one back
 * is the glossary's problem and it is handled there, in decisionLabel, which gives both spellings
 * the same words.
 */
export type FraudDecision = 'APPROVE' | 'DECLINE' | 'ANNOTATE';

/**
 * The body of a decision: the verdict, the analyst's own comment on it, and the notes.
 *
 * TWO FIELDS LEFT THIS BODY and neither is coming back, which is worth stating here because both
 * desks were filling them in.
 *
 * `tags` went because nothing on either platform could produce one. Both desks sent back the
 * empty list they had just been read, one as `[]` and one as nothing at all, and the server read
 * those two as opposite instructions, so one desk cleared the column on every decision and the
 * other left it alone. See {@link AlertInfo.tags}, which is still read.
 *
 * `assignee` went because it has a route of its own, {@link takeAlert} and {@link releaseAlert}.
 * Kept here it would be a second writer of one field with the opposite convention about a blank,
 * and echoing back the assignee the desk had read is enough to resurrect an assignment a
 * colleague cleared in the meantime.
 *
 * `reason` is what the analyst wrote about THIS decision, and it rides with all three verdicts
 * rather than belonging to the refusal. Nothing on a screen may call it the reason for declining.
 */
export interface FraudDecisionRequest {
    decision: FraudDecision;
    reason?: string;
    notes?: string;
}

export interface AlertFilters {
    state?: string;
    minAmount?: string;
    maxAmount?: string;
    createdFrom?: string;
    createdTo?: string;
    assignee?: string;

    /**
     * Transfer statuses whose alerts should not be listed.
     *
     * An exclusion, not a selection: the desk hides withdrawn payments, and the day a new
     * transfer status appears it must show up in the queue rather than disappear from it
     * because nobody remembered to add it to a list of wanted ones.
     */
    excludeTransferStatus?: string[];
}

/**
 * The queue's query string, built once for the two routes that have to be asked the same question.
 *
 * The hidden list answers "which alerts is this filter keeping off the screen", which is only an
 * answer while both requests carry the same filters. Two builders drifting by one parameter would
 * make the two numbers stop adding up, and the collision they exist to explain would look like a
 * miscount instead.
 */
function queueQuery(filters: AlertFilters, page: number, size: number): URLSearchParams {
    const params = new URLSearchParams();

    if (filters.state) params.set('state', filters.state);
    if (filters.minAmount) params.set('minAmount', filters.minAmount);
    if (filters.maxAmount) params.set('maxAmount', filters.maxAmount);
    if (filters.createdFrom) params.set('createdFrom', filters.createdFrom);
    if (filters.createdTo) params.set('createdTo', filters.createdTo);
    if (filters.assignee) params.set('assignee', filters.assignee);
    // append, not set: the parameter is repeatable and each status is its own value.
    for (const status of filters.excludeTransferStatus ?? []) {
        params.append('excludeTransferStatus', status);
    }

    return applyPaging(params, page, size);
}

export async function fetchAlerts(
    filters: AlertFilters = {},
    page = 0,
    size = DEFAULT_PAGE_SIZE,
): Promise<AlertQueueResponse> {
    const qs = queueQuery(filters, page, size);
    const res = await apiFetch(`${API_BASE}/fraud/alerts?${qs.toString()}`);
    return handle<AlertQueueResponse>(res);
}

/**
 * The alerts the caller's own `excludeTransferStatus` is keeping off the queue, and only those.
 *
 * It exists for one sight the desk could not explain. With the state filter on Cleared and
 * withdrawn payments hidden, the list answers nothing while the counters strip beside it still
 * says Cleared 1, because the counters count the whole queue and the exclusion is applied to the
 * rows. This route says where that one alert went, so the strip can be reconciled on screen
 * instead of read as a miscount.
 *
 * Send it the filters the queue was sent. Excluding nothing is not an error and not the whole
 * queue: it answers no rows and a total of zero, which is the truthful answer to "what is being
 * hidden" when nothing is.
 *
 * A page rather than the queue's envelope, because there are no counters to send: they count the
 * queue and are the number this list is being reconciled against.
 */
export async function fetchHiddenAlerts(
    filters: AlertFilters = {},
    page = 0,
    size = DEFAULT_PAGE_SIZE,
): Promise<Page<AlertQueueItem>> {
    const qs = queueQuery(filters, page, size);
    const res = await apiFetch(`${API_BASE}/fraud/alerts/hidden?${qs.toString()}`);
    return handle<Page<AlertQueueItem>>(res);
}

export async function fetchAlertDetail(id: number): Promise<AlertDetail> {
    const res = await apiFetch(`${API_BASE}/fraud/alerts/${id}`);
    return handle<AlertDetail>(res);
}

/**
 * The customer's payments as the desk reads them, a page at a time.
 *
 * The same rows as {@link AlertDetail.history} and a different promise. The detail carries at most
 * ten of them and says nothing about how many there are; this counts the lot, so the panel can say
 * which ten of how many it is showing and offer the rest. Scope is the alert's own: the customer
 * behind it, every account they hold, newest first, and reachable through the alert and nothing
 * else.
 *
 * A panel that pages through a history must read this rather than re-read the alert. Turning a
 * page on the detail would make the server rebuild the alert, the payment, the account and the
 * customer to answer a question about none of them.
 */
export async function fetchAlertHistory(
    id: number,
    page = 0,
    size = DEFAULT_PAGE_SIZE,
): Promise<Page<HistoryItem>> {
    const qs = applyPaging(new URLSearchParams(), page, size);
    const res = await apiFetch(`${API_BASE}/fraud/alerts/${id}/history?${qs.toString()}`);
    return handle<Page<HistoryItem>>(res);
}

export async function postFraudDecision(
    id: number,
    payload: FraudDecisionRequest,
): Promise<AlertDetail> {
    const res = await apiFetch(`${API_BASE}/fraud/alerts/${id}/decision`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    return handle<AlertDetail>(res);
}

/**
 * Takes the alert into the name of whoever is signed in.
 *
 * NO BODY, and that is the design rather than an omission: the only name this can write is the
 * session's, the same rule the recorded verdict already follows, which is what makes a route open
 * to every analyst safe to leave open. There is no directory of analysts in this application and
 * therefore no way to hand work to a named colleague; what a desk offers is this control and a
 * filter of mine against all.
 *
 * Answers the whole detail, exactly as a decision does, so the screen needs no second call to
 * find out what it now holds.
 */
export async function takeAlert(id: number): Promise<AlertDetail> {
    const res = await apiFetch(`${API_BASE}/fraud/alerts/${id}/assignment`, { method: 'POST' });
    return handle<AlertDetail>(res);
}

/**
 * Gives the alert back to the queue: the answering detail carries a null assignee.
 *
 * Any analyst may release any alert, including one held by somebody else. Deliberate at the
 * server: an alert held by an analyst who has gone home must not be able to hold up the queue.
 */
export async function releaseAlert(id: number): Promise<AlertDetail> {
    const res = await apiFetch(`${API_BASE}/fraud/alerts/${id}/assignment`, { method: 'DELETE' });
    return handle<AlertDetail>(res);
}
