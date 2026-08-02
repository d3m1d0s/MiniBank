// src/api.ts

export type ApiError = Error & { code?: string; status?: number };

/** True only for a failure that carries a server response, not for a network error. */
export function isApiError(e: unknown): e is ApiError {
    return e instanceof Error && typeof (e as ApiError).status === 'number';
}

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
        default:
            return [error.message || 'Unexpected error while creating payment.'];
    }
}

export interface AccountSummary {
    id: number;
    iban: string;
    balance: string;
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
    chargedAmount: string;
    newBalance: string;
    feeAmount: string;
    authorizationRequired: boolean;
}

// === UC05 DTOs (keep types flexible so minor backend differences do not break the UI) ===

export interface WaitingTransferItem {
    id: number;
    sourceIban?: string;
    targetIban?: string;
    amount?: string;
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
    fromBalance: string;
    toIban: string;
    amount: string;
    feeAmount: string;
    status: string;
    createdAt: string;
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
    chargedAmount: string | null;   // null if no funds were charged yet
    newBalance: string;             // always represents the current account balance
    declineReason: string | null;   // decline reason text for DECLINED, otherwise null
}

const API_BASE = 'http://localhost:8080/api';

let currentSessionId: string | null = null;

export function setSessionId(id: string | null) {
    currentSessionId = id;
}

/**
 * Low-level fetch wrapper that automatically attaches the current session header.
 */
async function apiFetch(input: RequestInfo, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers || {});
    if (currentSessionId) {
        headers.set('X-Session-Id', currentSessionId);
    }
    return fetch(input, { ...init, headers });
}

export async function login(payload: LoginRequest): Promise<LoginResponse> {
    const res = await fetch(`${API_BASE}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    const data = await handle<LoginResponse>(res);
    setSessionId(data.sessionId);
    return data;
}

export function logoutSession() {
    setSessionId(null);
}

let onSessionExpired: (() => void) | null = null;

/**
 * Registered by the app shell. Without it a rejected session leaves a signed-in UI whose
 * every request fails, and the only way back to the sign-in screen is a page reload.
 */
export function setSessionExpiredHandler(fn: (() => void) | null) {
    onSessionExpired = fn;
}

/**
 * Common response handler:
 * - throws an ApiError carrying { code, status } for non-2xx responses
 * - parses JSON on success, or returns plain text as a fallback
 */
async function handle<T>(res: Response): Promise<T> {
    const text = await res.text();

    if (!res.ok) {
        let code: string | undefined;
        let message = '';

        // The parse must not wrap the throw. It used to: `throw error` sat inside this try,
        // so the catch below swallowed the error that carried the code and replaced it with
        // `new Error(rawBody)`. That is why every error box in this app rendered a literal
        // JSON blob and why error.code was always undefined.
        if (text) {
            try {
                const parsed = JSON.parse(text) as { code?: string; message?: string };
                code = parsed.code;
                message = parsed.message ?? '';
            } catch {
                message = text;
            }
        }

        const error = new Error(message || res.statusText || 'Request failed') as ApiError;
        error.code = code;
        error.status = res.status;

        // AUTH_REQUIRED means the session is gone; AUTH_FAILED is a rejected sign-in on the
        // login screen itself and must not trigger this.
        if (res.status === 401 && code === 'AUTH_REQUIRED') {
            setSessionId(null);
            onSessionExpired?.();
        }

        throw error;
    }

    // Successful response
    if (!text) {
        // For empty body (for example 204 No Content)
        return {} as T;
    }

    try {
        return JSON.parse(text) as T;
    } catch {
        // Backend returned non-JSON payload, return it as text
        return text as unknown as T;
    }
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

export interface AlertQueueItem {
    id: number;
    alertCode: string;
    transferCode: string;
    state: string;
    // The transfer's status, not the alert's. Without it a payment that has already gone looks
    // identical in the queue to one still held for review.
    transferStatus: string;
    amount: string;
    currency: string;
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
    fromBalance: string;
    toIban: string;
    amount: string;
    feeAmount: string;
    currency: string;
    createdAt: string | null;
    authMethod: string | null;
}

export interface HistoryItem {
    id: number;
    createdAt: string | null;
    amount: string;
    currency: string;
    status: string;
    toIban: string;
    declineReason: string | null;
}

export interface AlertDetail {
    alert: AlertInfo;
    transfer: TransferInfo;
    history: HistoryItem[];
}

export type FraudDecision = 'APPROVE' | 'DECLINE' | 'REQUEST_CONFIRMATION';

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
}

export interface LoginRequest {
    username: string;
    password: string;
}

export interface LoginResponse {
    sessionId: string;
    username: string;
    role: 'CUSTOMER' | 'FRAUD_ANALYST' | 'OPERATIONS' | 'MANAGEMENT';
    customerId: number | null;
}

export async function fetchAlerts(
    filters: AlertFilters = {},
): Promise<AlertQueueResponse> {
    const params = new URLSearchParams();

    if (filters.state) params.set('state', filters.state);
    if (filters.minAmount) params.set('minAmount', filters.minAmount);
    if (filters.maxAmount) params.set('maxAmount', filters.maxAmount);
    if (filters.createdFrom) params.set('createdFrom', filters.createdFrom);
    if (filters.createdTo) params.set('createdTo', filters.createdTo);
    if (filters.assignee) params.set('assignee', filters.assignee);

    const qs = params.toString();
    const url = qs
        ? `${API_BASE}/fraud/alerts?${qs}`
        : `${API_BASE}/fraud/alerts`;

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
