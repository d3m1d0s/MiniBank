// src/api.ts

export type ApiError = Error & { code?: string };

export function isApiError(e: unknown): e is ApiError {
    return e instanceof Error;
}

export function mapPaymentError(error: ApiError): string[] {
    let code = error.code;
    let message = error.message || '';

    // Если код не проставлен, но message выглядит как JSON – попробуем его распарсить
    if (!code && message && message.trim().startsWith('{')) {
        try {
            const parsed = JSON.parse(message) as { code?: string; message?: string };
            if (parsed.code) code = parsed.code;
            if (parsed.message) message = parsed.message;
        } catch {
            // не JSON – оставляем как есть
        }
    }

    switch (code) {
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
        case 'DAILY_LIMIT_EXCEEDED':
            return [
                'Daily limit for this account has been exceeded.',
                'You can try a lower amount or wait until tomorrow.',
            ];
        default:
            // fallback – используем уже очищенный message
            return [message || 'Unexpected error while creating payment.'];
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

// === UC05 DTOs (держим типы гибкими, чтобы не упираться в расхождения) ===

export interface WaitingTransferItem {
    id: number;
    sourceIban?: string;
    targetIban?: string;
    amount?: string;
    createdAt?: string;
    authMethod?: string;
    [key: string]: unknown;
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
    chargedAmount: string | null;   // null, если ничего не списывали
    newBalance: string;             // всегда актуальный баланс счёта
    declineReason: string | null;   // текст причины при DECLINED, иначе null
}



const API_BASE = 'http://localhost:8080/api';

let currentSessionId: string | null = null;

export function setSessionId(id: string | null) {
    currentSessionId = id;
}


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


async function handle<T>(res: Response): Promise<T> {
    const text = await res.text();

    if (!res.ok) {
        if (text) {
            // пробуем распарсить AppError { code, message }
            try {
                const parsed = JSON.parse(text) as { code?: string; message?: string };
                const error = new Error(parsed.message || parsed.code || res.statusText);
                if (parsed.code) {
                    (error as any).code = parsed.code;
                }
                throw error;
            } catch {
                // ответ не JSON
                throw new Error(text || res.statusText);
            }
        }

        throw new Error(res.statusText);
    }

    // успешный ответ
    if (!text) {
        // на случай 204 / пустого ответа
        return {} as T;
    }

    try {
        return JSON.parse(text) as T;
    } catch {
        // если вдруг вернулся не-JSON
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
            // бэкенду нужен только otp, id он берёт из URL
            body: JSON.stringify({ otp: payload.otp }),
        },
    )
    return handle<AuthorizePaymentResult>(res)
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

