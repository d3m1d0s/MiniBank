/**
 * The fraud desk's half of the API: what the three endpoints return, and how to call them.
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
    assignee: string | null;
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
 * What an analyst can post about an alert.
 *
 * The third was called REQUEST_CONFIRMATION, which named something it has never done: it asks
 * nobody for anything, it writes the assignee, the tags and the notes, and it takes no decision.
 * The server accepts both spellings so the rename can reach the two desks in any order, but only
 * ANNOTATE is on this list: the list is what a client may send, and leaving the old name here is
 * what would let a screen go on sending it.
 *
 * Rows written before the rename keep the old spelling in the database forever. Reading one back
 * is the glossary's problem and it is handled there, in decisionLabel, which gives both spellings
 * the same words.
 */
export type FraudDecision = 'APPROVE' | 'DECLINE' | 'ANNOTATE';

export interface FraudDecisionRequest {
    decision: FraudDecision;
    reason?: string;
    assignee?: string;
    tags?: string[];
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

export async function fetchAlerts(
    filters: AlertFilters = {},
    page = 0,
    size = DEFAULT_PAGE_SIZE,
): Promise<AlertQueueResponse> {
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

    applyPaging(params, page, size);

    const res = await apiFetch(`${API_BASE}/fraud/alerts?${params.toString()}`);
    return handle<AlertQueueResponse>(res);
}

export async function fetchAlertDetail(id: number): Promise<AlertDetail> {
    const res = await apiFetch(`${API_BASE}/fraud/alerts/${id}`);
    return handle<AlertDetail>(res);
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
