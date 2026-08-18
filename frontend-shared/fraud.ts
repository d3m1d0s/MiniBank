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

export interface AlertQueueResponse {
    items: AlertQueueItem[];
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
    amount: Money;
    feeAmount: Money;
    createdAt: string | null;
    authMethod: string | null;
}

export interface HistoryItem {
    id: number;
    createdAt: string | null;
    amount: Money;
    status: string;
    toIban: string;
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

export async function fetchAlerts(filters: AlertFilters = {}): Promise<AlertQueueResponse> {
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

    const qs = params.toString();
    const url = qs ? `${API_BASE}/fraud/alerts?${qs}` : `${API_BASE}/fraud/alerts`;

    const res = await apiFetch(url);
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
