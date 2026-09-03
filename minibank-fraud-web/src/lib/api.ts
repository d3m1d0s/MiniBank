/**
 * Everything this application asks of the API, in one import for its screens.
 *
 * All of it is now shared with the customer application, which carries the same fraud desk for a
 * signed-in analyst. Both used to hold their own copy of every type and every call below, and the
 * copies were maintained in parallel: the same defect in the response handler had to be found
 * twice, and retiring a field from the server meant remembering both files.
 *
 * This file stays as a barrel rather than being deleted, so a screen keeps importing from './api'
 * and one place still says what this application uses.
 */

export type { ApiError, LoginRequest, LoginResponse } from '@shared/wire/http';
export {
    isApiError,
    setSessionId,
    setSessionExpiredHandler,
    login,
    logoutSession,
} from '@shared/wire/http';

import { API_BASE, apiFetch, handle } from '@shared/wire/http';

/*
 * Who is signed in, described next door because the route is not the customer's.
 *
 * `GET /api/me` is not guarded by role, so both applications ask it about whoever is at the
 * keyboard: the customer application to greet somebody by name rather than by the login they
 * typed, and this window to name the analyst at the desk. The record is declared once in the
 * shared directory for the usual reason, that a second declaration of a wire shape is how one
 * platform comes to be reading a field the other has never heard of.
 */
export type { Me } from '@shared/wire/customer';
import type { Me } from '@shared/wire/customer';

/**
 * The person behind the current session.
 *
 * An analyst has no customer record, so `name` comes back null for this window's own role and the
 * title bar keeps the login. Asked anyway, and by the same route the customer application asks:
 * the fact that the answer is presently empty for `fraud` is the server's to change, and a window
 * that never asks would go on printing a login after it does.
 */
export async function fetchMe(): Promise<Me> {
    const res = await apiFetch(`${API_BASE}/me`);
    return handle<Me>(res);
}

export type { Money } from '@shared/text/money';
import type { Money } from '@shared/text/money';
/*
 * The reader as well as the writer now. parseAmount used to be the customer application's alone,
 * and this desk carried a second copy of it under the name readAmount because one application may
 * not import another's src/: two parsers for the one convention that decides whether 1,000 is a
 * thousand crowns or one. There is one, it is shared, and both amount boxes on this desk read
 * exactly what the payment form reads.
 */
export { formatMoney, parseAmount } from '@shared/text/money';

/*
 * A page of a list as the API sends one, which is how the alert queue now arrives: the rows, the
 * page and size that were asked for, and the total behind the filters.
 */
export type { Page } from '@shared/logic/paging';

export type {
    AlertQueueItem,
    AlertCounters,
    AlertQueueResponse,
    AlertInfo,
    /*
     * One entry of an alert's journal, which arrives inside the detail rather than on a route of
     * its own. It is listed here because this window draws it, and the barrel is where this
     * application says what it reads.
     */
    AlertNote,
    TransferInfo,
    HistoryItem,
    AlertDetail,
    FraudDecision,
    FraudDecisionRequest,
    AlertFilters,
} from '@shared/wire/fraud';

/*
 * Six calls where there were three. The three new ones are what the desk needed to stop
 * contradicting itself: the hidden list reconciles the counters with a queue its own filter has
 * emptied, the history route carries the page, the size and the total the alert detail never did,
 * and the assignment pair is the only way a name can be written into the column this desk has
 * always printed and filtered on.
 */
export {
    fetchAlerts,
    fetchAlertDetail,
    fetchAlertHistory,
    fetchHiddenAlerts,
    postFraudDecision,
    releaseAlert,
    takeAlert,
} from '@shared/wire/fraud';

/*
 * The two that ask nothing of the bank, listed here all the same because they are the rule the
 * three decision buttons are dead by and the body those buttons send. Both were written out by
 * hand on each desk, so the same verdict could be offered on one platform and refused on the
 * other, and a blank box could mean two different things on the wire. They stand beside the call
 * they belong to, which is postFraudDecision above.
 */
export { buildDecisionRequest, decisionAllowed } from '@shared/wire/fraud';

/* ============================ THE CUSTOMER'S OWN ROUTES ============================
 *
 * The shapes are read from @shared/customer, where they were put when the customer application was
 * the only one drawing them, with the note that this window grows the same screens later. It has.
 * What is written out here is the fetching, which that module says each application owns, and it is
 * the same nine calls the customer application makes against the same nine addresses.
 *
 * Nine functions in two places is nine functions in two places, and the argument for sharing them
 * is now the one @shared/customer makes about fetchMe in its own header: a call is shared when both
 * platforms make it. Both platforms make these. Moving them is a change to a file this pass does
 * not own, so it is handed over rather than done, and until it happens the wire description above
 * is what keeps the two copies from meaning different things.
 */

export type {
    AccountSummary,
    Address,
    DailyOutflow,
    DispatchState,
    MyAccountsResponse,
    PaymentQuote,
    TransferDetails,
    UserRole,
} from '@shared/wire/customer';
import type { MyAccountsResponse, PaymentQuote, TransferDetails } from '@shared/wire/customer';

/* The address book, and the page envelope a paged list arrives in. */
export type { Beneficiary } from '@shared/logic/paging';
import type { Beneficiary, Page } from '@shared/logic/paging';
import { applyPaging } from '@shared/logic/paging';
/* The customer's own history row, which is the analyst's row read by the person who made it. */
import type { HistoryItem } from '@shared/wire/fraud';

/* A payment as it is submitted, with exactly one of the two destinations set: the server refuses a
   request that names both, because a caller that has named both has lost track of which it meant. */
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

/** A row of `GET /api/me/waiting-transfers`, field for field as the server sends one. */
export interface WaitingTransferItem {
    id: number;
    toIban: string;
    amount: Money;
    createdAt: string;
    /** Null on a transfer with no authorization method recorded. */
    authMethod: string | null;
    /* WAITING_AUTH or HELD_FOR_REVIEW, left as a plain string like every other status on this wire
       so an unrecognised value renders rather than failing to parse. */
    status: string;
}

/** True while the bank is still reviewing this payment, so the customer cannot confirm it yet. */
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
    /** Null where no funds were charged. */
    chargedAmount: Money | null;
    /** Always the current balance of the account, whatever the outcome was. */
    newBalance: Money;
    /** The reason on a DECLINED payment, and null otherwise. */
    declineReason: string | null;
}

/** The customer's accounts, and the one day all of them share. */
export async function getMyAccounts(): Promise<MyAccountsResponse> {
    const res = await apiFetch(`${API_BASE}/me/accounts`);
    return handle<MyAccountsResponse>(res);
}

/**
 * What this payment would cost, priced by the bank before anything is sent.
 *
 * Asked rather than computed. The tariff is a step function, so a heller past a boundary is ten
 * crowns, and a copy of it in the browser goes on quoting last month's price after the bank changes
 * its mind. The answer also carries the bank's real decision about a one time code, which depends
 * on where the money is going as well as on how much.
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
 * this payee is reviewed is deliberately not on this wire, so there is nothing here for a screen to
 * leak. Not paged, because an address book is a handful of rows beside a form.
 */
export async function fetchMyBeneficiaries(): Promise<Beneficiary[]> {
    const res = await apiFetch(`${API_BASE}/me/beneficiaries`);
    return handle<Beneficiary[]>(res);
}

export async function createPayment(payload: NewPaymentRequest): Promise<NewPaymentResult> {
    const res = await apiFetch(`${API_BASE}/payments`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    return handle<NewPaymentResult>(res);
}

/** One page of the transfers waiting on the customer or on the bank, newest first. */
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
 * A row is a HistoryItem, the same shape this desk already reads beside an alert, because it is the
 * same row read by its owner. What differs is who is being addressed, and that is settled by the
 * glossary's audience parameter rather than by a second row type.
 */
export async function fetchMyTransfers(page: number, size: number): Promise<Page<HistoryItem>> {
    const qs = applyPaging(new URLSearchParams(), page, size);
    const res = await apiFetch(`${API_BASE}/me/transfers?${qs.toString()}`);
    return handle<Page<HistoryItem>>(res);
}

export async function fetchTransferDetails(id: number): Promise<TransferDetails> {
    const res = await apiFetch(`${API_BASE}/transfers/${id}`);
    return handle<TransferDetails>(res);
}

export async function confirmAuthorization(
    payload: AuthorizePaymentRequest,
): Promise<AuthorizePaymentResult> {
    const res = await apiFetch(`${API_BASE}/transfers/${payload.transferId}/authorize`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        /* The id is in the address; the body carries the code and nothing else. */
        body: JSON.stringify({ otp: payload.otp }),
    });
    return handle<AuthorizePaymentResult>(res);
}

export async function cancelTransfer(id: number): Promise<AuthorizePaymentResult> {
    const res = await apiFetch(`${API_BASE}/transfers/${id}/cancel`, { method: 'POST' });
    return handle<AuthorizePaymentResult>(res);
}
