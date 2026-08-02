export type ApiError = Error & { code?: string; status?: number };

let currentSessionId: string | null = null;

export function setSessionId(id: string | null) {
    currentSessionId = id;
}

let onSessionExpired: (() => void) | null = null;

/** Registered by the app shell so a rejected session returns to the sign-in screen. */
export function setSessionExpiredHandler(fn: (() => void) | null) {
    onSessionExpired = fn;
}

async function apiFetch(input: RequestInfo, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers || {});
    if (currentSessionId) headers.set('X-Session-Id', currentSessionId);
    return fetch(input, { ...init, headers });
}

async function handle<T>(res: Response): Promise<T> {
    const text = await res.text();

    if (!res.ok) {
        let code: string | undefined;
        let message = '';

        // The parse must not wrap the throw. It used to: the assignment of `code` was
        // annihilated by the catch below, which replaced the whole error with the raw
        // response body. This app never successfully attached a code at all.
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

        if (res.status === 401 && code === 'AUTH_REQUIRED') {
            setSessionId(null);
            onSessionExpired?.();
        }

        throw error;
    }

    if (!text) return {} as T;

    try {
        return JSON.parse(text) as T;
    } catch {
        return text as unknown as T;
    }
}

// --- Auth ---
export interface LoginRequest { username: string; password: string; }
export interface LoginResponse {
    sessionId: string;
    username: string;
    role: 'CUSTOMER' | 'FRAUD_ANALYST' | 'OPERATIONS' | 'MANAGEMENT';
    customerId: number | null;
}

export async function login(payload: LoginRequest): Promise<LoginResponse> {
    const res = await fetch(`/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    const data = await handle<LoginResponse>(res);
    setSessionId(data.sessionId);
    return data;
}

/**
 * Closes the session at both ends. Until the server put logout behind authentication this
 * could not be called safely at all, so it only ever cleared the local variable and the
 * session stayed fully usable on the server after every Logout press.
 *
 * Fire and forget on purpose: a session that has already expired answers 401 AUTH_REQUIRED,
 * and routing that through handle() would show "you have been signed out" to somebody who
 * just pressed Logout.
 */
export function logoutSession() {
    const id = currentSessionId;
    setSessionId(null);
    if (id) {
        void fetch('/api/auth/logout', { method: 'POST', headers: { 'X-Session-Id': id } })
            .catch(() => { /* the session is gone locally either way */ });
    }
}

// --- Fraud desk ---
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

export interface AlertCounters { newCount: number; suspiciousCount: number; okCount: number; }
export interface AlertQueueResponse { items: AlertQueueItem[]; counters: AlertCounters; }

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

export interface AlertDetail { alert: AlertInfo; transfer: TransferInfo; history: HistoryItem[]; }

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

export async function fetchAlerts(filters: AlertFilters = {}): Promise<AlertQueueResponse> {
    const params = new URLSearchParams();
    if (filters.state) params.set('state', filters.state);
    if (filters.minAmount) params.set('minAmount', filters.minAmount);
    if (filters.maxAmount) params.set('maxAmount', filters.maxAmount);
    if (filters.createdFrom) params.set('createdFrom', filters.createdFrom);
    if (filters.createdTo) params.set('createdTo', filters.createdTo);
    if (filters.assignee) params.set('assignee', filters.assignee);

    const qs = params.toString();
    const url = qs ? `/api/fraud/alerts?${qs}` : `/api/fraud/alerts`;

    const res = await apiFetch(url);
    return handle<AlertQueueResponse>(res);
}

export async function fetchAlertDetail(id: number): Promise<AlertDetail> {
    const res = await apiFetch(`/api/fraud/alerts/${id}`);
    return handle<AlertDetail>(res);
}

export async function postFraudDecision(id: number, payload: FraudDecisionRequest): Promise<AlertDetail> {
    const res = await apiFetch(`/api/fraud/alerts/${id}/decision`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    return handle<AlertDetail>(res);
}
