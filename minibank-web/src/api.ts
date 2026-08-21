// src/api.ts

export type { ApiError, LoginRequest, LoginResponse } from '@shared/http';
export { isApiError, setSessionId, setSessionExpiredHandler, login, logoutSession } from '@shared/http';

import { API_BASE, apiFetch, handle } from '@shared/http';
import { applyPaging } from '@shared/paging';
import type { Beneficiary, Page } from '@shared/paging';
import type { HistoryItem } from '@shared/fraud';

/**
 * The wire shapes the paged lists and the payee list arrive in. Declared in the shared layer,
 * because the workstation reads the same page envelope off the alert queue, and re-exported here
 * so a screen of this application keeps importing everything it needs from one module.
 */
export type { Beneficiary, Page } from '@shared/paging';

// The sentence for a failed call used to be written here, in mapPaymentError, and again twice on
// the authorization screen and again in the fraud desk's catch blocks. Four tables that did not
// carry the same branches. They are one table now, in @shared/apiErrors, and a screen asks it for
// the words with the name of the call it made.

/** Shared with the analyst app. Re-exported so call sites in this app import from one place. */
export type { Money } from '@shared/money';
import type { Money } from '@shared/money';

/**
 * The customer's own wire, described next door and re-exported here.
 *
 * Two of these records used to be declared in this file and both had drifted from what the server
 * sends: an account was three fields where six arrive, and a payment read in full declared half
 * its keys optional where the server always sends them and means null by it. The workstation grows
 * these same screens later and reads the same routes, so the description of the wire belongs where
 * both platforms can see it; what stays here is the fetching.
 */
export type {
    AccountSummary,
    Address,
    DispatchState,
    Me,
    PaymentQuote,
    TransferDetails,
    UserRole,
} from '@shared/customer';
import type { AccountSummary, Me, PaymentQuote, TransferDetails } from '@shared/customer';

/**
 * A payment as it is submitted, with exactly one of the two destinations set.
 *
 * Both are optional here and the server refuses a request that names both, which is the same
 * decision written on both sides: a saved payee and a typed account number are two different
 * people as often as they are one, and a caller that has named both has lost track of which it
 * meant. `beneficiaryId` is what reaches the trusted branch of the risk rules; a typed IBAN has
 * no payee row behind it and never can.
 */
export interface NewPaymentRequest {
    sourceAccountId: number;
    targetIban?: string;
    beneficiaryId?: number;
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
 * It used to be a guess: `targetIban` and `sourceIban` were declared, the server sent
 * `beneficiaryIban` and has never sent a source at all, everything was optional, and an index
 * signature caught whatever else arrived. The screen then read the beneficiary through a cast to
 * any - the one line eslint reported - because the field it wanted was not on the type it had.
 * A type that describes the wire needs no cast and no fallback, and a field the server stops
 * sending becomes a compile error instead of a blank column.
 *
 * The destination is `toIban`, which is what every other DTO on this wire has always called it.
 * This was the one record that called it `beneficiaryIban`, and the name was wrong as well as
 * inconsistent: a payment typed straight into the IBAN box has no beneficiary behind it at all.
 */
export interface WaitingTransferItem {
    id: number;
    toIban: string;
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

/**
 * Who is signed in, by name rather than by login.
 *
 * The header band had only the string that was typed into the sign-in box, so a customer whose
 * record says Alice Novakova was greeted as `alice`. Not guarded by role on the server, which is
 * why the analyst desk can use it too: an analyst has no customer behind the login and gets nulls
 * for the four customer fields.
 */
export async function fetchMe(): Promise<Me> {
    const res = await apiFetch(`${API_BASE}/me`);
    return handle<Me>(res);
}

export async function getMyAccounts(): Promise<AccountSummary[]> {
    const res = await apiFetch(`${API_BASE}/me/accounts`);
    return handle<AccountSummary[]>(res);
}

/**
 * What this payment would cost, priced by the bank before anything is sent.
 *
 * Asked rather than computed. The tariff is a step function, so a heller past a boundary is ten
 * crowns, and a copy of it in the browser is a copy that goes on quoting last month's price after
 * the bank changes its mind. The answer also carries the bank's real decision about a one time
 * code, which depends on where the money is going as well as on how much.
 *
 * @param beneficiaryId the saved payee, when one was chosen. It changes the answer rather than
 *                      decorating it: the same amount can settle at once to a payee the bank
 *                      trusts and ask for a code when it goes to a typed account number
 */
export async function fetchPaymentQuote(
    sourceAccountId: number,
    amountCzk: number,
    beneficiaryId?: number | null,
): Promise<PaymentQuote> {
    const params = new URLSearchParams();
    params.set('sourceAccountId', String(sourceAccountId));
    params.set('amountCzk', String(amountCzk));
    if (beneficiaryId != null) {
        params.set('beneficiaryId', String(beneficiaryId));
    }

    const res = await apiFetch(`${API_BASE}/payments/quote?${params.toString()}`);
    return handle<PaymentQuote>(res);
}

/**
 * The customer's saved payees, already ordered by name on the server.
 *
 * Three fields arrive and there is no fourth: the trusted flag that decides whether a payment to
 * this payee is reviewed is deliberately not on this wire, so there is nothing here for a screen
 * to leak. Not paged - an address book is a handful of rows beside a form.
 */
export async function fetchMyBeneficiaries(): Promise<Beneficiary[]> {
    const res = await apiFetch(`${API_BASE}/me/beneficiaries`);
    return handle<Beneficiary[]>(res);
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

/**
 * One page of the transfers waiting on the customer or on the bank, newest first.
 *
 * It used to answer with the whole list and no bound at all. The page is asked for by number and
 * size and the answer says which it was, so the next request is derived from what arrived rather
 * than from the rows on screen; see appendPage in @shared/paging for why those two part company.
 */
export async function fetchWaitingTransfers(
    page: number,
    size: number,
): Promise<Page<WaitingTransferItem>> {
    const qs = applyPaging(new URLSearchParams(), page, size);
    const res = await apiFetch(`${API_BASE}/me/waiting-transfers?${qs.toString()}`);
    return handle<Page<WaitingTransferItem>>(res);
}

/**
 * One page of every payment the customer has made, from every account they hold, newest first.
 *
 * A row of this list is a HistoryItem, the same shape the analyst reads beside an alert, because
 * it is the same row read by its owner. The one thing that differs is who is being addressed, and
 * that is settled on this side by the glossary's audience parameter.
 */
export async function fetchMyTransfers(
    page: number,
    size: number,
): Promise<Page<HistoryItem>> {
    const qs = applyPaging(new URLSearchParams(), page, size);
    const res = await apiFetch(`${API_BASE}/me/transfers?${qs.toString()}`);
    return handle<Page<HistoryItem>>(res);
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

export {
    fetchAlerts,
    fetchAlertDetail,
    fetchAlertHistory,
    fetchHiddenAlerts,
    postFraudDecision,
    releaseAlert,
    takeAlert,
} from '@shared/fraud';
