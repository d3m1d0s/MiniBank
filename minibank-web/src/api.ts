// src/api.ts

export type { ApiError, LoginRequest, LoginResponse } from '@shared/http';
export { isApiError, setSessionId, setSessionExpiredHandler, login, logoutSession } from '@shared/http';

import type { ApiError } from '@shared/http';
import { API_BASE, apiFetch, handle } from '@shared/http';

export function mapPaymentError(error: ApiError): string[] {
    // No JSON re-parse of the message any more: that only existed to dig the code back out
    // of a string that handle() had mangled, and handle() no longer mangles it.
    switch (error.code) {
        case 'INVALID_IBAN':
            return [
                'The IBAN is not valid.',
                'Please check the country code and all digits.',
            ];
        case 'INSUFFICIENT_FUNDS':
            return [
                'There are not enough funds on the selected account.',
                'Try lowering the amount or use a different account.',
            ];
        case 'VALIDATION_ERROR':
            return [
                'Some of the payment details are not valid.',
                'Check the amount and the beneficiary IBAN.',
            ];
        case 'NOT_FOUND':
            return ['The selected account is not available. Reload the page and try again.'];
        case 'FORBIDDEN':
            return ['You are not allowed to send a payment from this account.'];
        // A6. Another transaction changed one of the accounts this payment touches between the
        // server reading a balance and writing the new one, so the write was refused. Resending
        // is the right action, which is what makes this different from CONFLICT.
        case 'CONCURRENT_MODIFICATION':
            return [
                'Another change was applied to this payment first.',
                'Nothing was charged. Please send the payment again.',
            ];
        default:
            return [error.message || 'Unexpected error while creating payment.'];
    }
}

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

// === UC05 DTOs (keep types flexible so minor backend differences do not break the UI) ===

export interface WaitingTransferItem {
    id: number;
    sourceIban?: string;
    targetIban?: string;
    amount?: Money;
    createdAt?: string;
    authMethod?: string;
    // 'WAITING_AUTH' or 'HELD_FOR_REVIEW'. Left as a plain string like every other status on
    // this wire, so an unrecognised value renders rather than failing to parse.
    status?: string;
    [key: string]: unknown;
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
     * policy until then. Before A14 this was recomputed on every read, so it could restate what
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
    authMethod?: string;
    triesLeft?: number;
    authValidUntil?: string;
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
