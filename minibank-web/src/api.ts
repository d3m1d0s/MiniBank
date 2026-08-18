// src/api.ts

export type { ApiError, LoginRequest, LoginResponse } from '@shared/http';
export { isApiError, setSessionId, setSessionExpiredHandler, login, logoutSession } from '@shared/http';

import { API_BASE, apiFetch, handle } from '@shared/http';

// The sentence for a failed call used to be written here, in mapPaymentError, and again twice on
// the authorization screen and again in the fraud desk's catch blocks. Four tables that did not
// carry the same branches. They are one table now, in @shared/apiErrors, and a screen asks it for
// the words with the name of the call it made.

/** Shared with the analyst app. Re-exported so call sites in this app import from one place. */
export type { Money } from '@shared/money';
import type { Money } from '@shared/money';

export interface AccountSummary {
    id: number;
    iban: string;
    balance: Money;
}

export interface NewPaymentRequest {
    sourceAccountId: number;
    targetIban: string;
    amountCzk: number;
    message: string;
}

export interface NewPaymentResult {
    transferId: number;
    status: string;
    chargedAmount: Money;
    newBalance: Money;
    feeAmount: Money;
    authorizationRequired: boolean;
}

// === UC05 DTOs ===

/**
 * A row of GET /api/me/waiting-transfers, field for field as WaitingTransferItemDto sends it.
 *
 * It used to be a guess: `targetIban` and `sourceIban` were declared, the server sends
 * `beneficiaryIban` and has never sent a source at all, everything was optional, and an index
 * signature caught whatever else arrived. The screen then read the beneficiary through a cast to
 * any - the one line eslint reported - because the field it wanted was not on the type it had.
 * A type that describes the wire needs no cast and no fallback, and a field the server stops
 * sending becomes a compile error instead of a blank column.
 *
 * `beneficiaryIban` is the server's own name for it and the only DTO on this wire that does not
 * call this field `toIban`; renaming it there is the backend's to do, not something to paper over
 * with two names here.
 */
export interface WaitingTransferItem {
    id: number;
    beneficiaryIban: string;
    amount: Money;
    createdAt: string;
    /** Null on a transfer with no authorization method recorded. */
    authMethod: string | null;
    // 'WAITING_AUTH' or 'HELD_FOR_REVIEW'. Left as a plain string like every other status on
    // this wire, so an unrecognised value renders rather than failing to parse.
    status: string;
}

/** True when the bank is still reviewing this payment, so the customer cannot confirm it yet. */
export function isUnderReview(status?: string | null): boolean {
    return status === 'HELD_FOR_REVIEW';
}

export interface TransferDetails {
    id: number;
    fromIban: string;
    fromBalance: Money;
    toIban: string;
    /**
     * What the transfer was charged once it has settled, and a quote from the current fee
     * policy until then. This used to be recomputed on every read, so it could restate what
     * a customer was charged last month the day the fee policy changed.
     */
    amount: Money;
    feeAmount: Money;
    status: string;
    createdAt: string;
    /** When the money moved. Null on a transfer that has not settled. */
    settledAt?: string | null;
    /**
     * The customer's own reference. Optional because a payment created before this was stored,
     * or created without one, has none. NewPaymentPage has sent this in the request body all
     * along; this is the first time it can be read back.
     */
    message?: string | null;
    /**
     * Why the payment was stopped, or null while it still might go through. The analyst could
     * already read this string in the alert history of the very same transfer; its owner could
     * not read it anywhere, which is what this field is here to end.
     */
    declineReason?: string | null;
    authMethod?: string;
    /**
     * How many one time codes are left, and the deadline for using one. Both are null unless the
     * transfer is WAITING_AUTH: they answer "how do I finish authorizing this", and a held
     * transfer was reporting three attempts beside a Confirm button that will not take one.
     */
    triesLeft?: number | null;
    authValidUntil?: string | null;
}

export interface AuthorizePaymentRequest {
    transferId: number;
    otp: string;
}

export interface AuthorizePaymentResult {
    transferId: number;
    status: string;
    chargedAmount: Money | null;    // null if no funds were charged yet
    newBalance: Money;              // always represents the current account balance
    declineReason: string | null;   // decline reason text for DECLINED, otherwise null
}

// === UC04 ===

export async function getMyAccounts(): Promise<AccountSummary[]> {
    const res = await apiFetch(`${API_BASE}/me/accounts`);
    return handle<AccountSummary[]>(res);
}

export async function createPayment(
    payload: NewPaymentRequest,
): Promise<NewPaymentResult> {
    const res = await apiFetch(`${API_BASE}/payments`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    return handle<NewPaymentResult>(res);
}

// === UC05 ===

export async function fetchWaitingTransfers(): Promise<WaitingTransferItem[]> {
    const res = await apiFetch(`${API_BASE}/me/waiting-transfers`);
    return handle<WaitingTransferItem[]>(res);
}

export async function fetchTransferDetails(
    id: number,
): Promise<TransferDetails> {
    const res = await apiFetch(`${API_BASE}/transfers/${id}`);
    return handle<TransferDetails>(res);
}

export async function confirmAuthorization(
    payload: AuthorizePaymentRequest,
): Promise<AuthorizePaymentResult> {
    const res = await apiFetch(
        `${API_BASE}/transfers/${payload.transferId}/authorize`,
        {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            // Backend needs only OTP in the body; transfer id is taken from URL
            body: JSON.stringify({ otp: payload.otp }),
        },
    );
    return handle<AuthorizePaymentResult>(res);
}

export async function cancelTransfer(
    id: number,
): Promise<AuthorizePaymentResult> {
    const res = await apiFetch(`${API_BASE}/transfers/${id}/cancel`, {
        method: 'POST',
    });
    return handle<AuthorizePaymentResult>(res);
}

// === DESK-1: Fraud Desk (alerts) ===
//
// The analyst's own application carries the same desk against the same three endpoints, so the
// shapes and the calls live in one place. Re-exported rather than imported directly by the
// screens, so a component keeps importing everything it needs from './api'.

export type {
    AlertQueueItem,
    AlertCounters,
    AlertQueueResponse,
    AlertInfo,
    TransferInfo,
    HistoryItem,
    AlertDetail,
    FraudDecision,
    FraudDecisionRequest,
    AlertFilters,
} from '@shared/fraud';

export { fetchAlerts, fetchAlertDetail, postFraudDecision } from '@shared/fraud';
