// src/api.ts
export interface AccountSummary {
    id: number;
    iban: string;
    balance: string;
}

export interface NewPaymentRequest {
    customerId?: number;
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
    sourceIban?: string;
    sourceBalance?: string;
    targetIban?: string;
    amount?: string;
    feeAmount?: string;
    status?: string;
    createdAt?: string;
    [key: string]: unknown;
}

export interface AuthorizePaymentRequest {
    transferId: number;
    otp: string;
}

export interface AuthorizePaymentResult {
    transferId: number;
    status: string;
    chargedAmount?: string;
    newBalance?: string;
    [key: string]: unknown;
}

const API_BASE = 'http://localhost:8080/api';

async function handle<T>(res: Response): Promise<T> {
    if (!res.ok) {
        // пробуем прочитать тело как JSON с AppError
        const text = await res.text();
        try {
            const parsed = JSON.parse(text) as { code?: string; message?: string };
            throw new Error(parsed.message || parsed.code || res.statusText);
        } catch {
            // не JSON
            throw new Error(text || res.statusText);
        }
    }
    // если тело пустое
    const text = await res.text();
    if (!text) return {} as T;
    return JSON.parse(text) as T;
}

// === UC04 ===

export async function fetchMyAccounts(): Promise<AccountSummary[]> {
    const res = await fetch(`${API_BASE}/me/accounts`);
    return handle<AccountSummary[]>(res);
}

export async function createPayment(
    payload: NewPaymentRequest,
): Promise<NewPaymentResult> {
    const res = await fetch(`${API_BASE}/payments`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    return handle<NewPaymentResult>(res);
}

// === UC05 ===

export async function fetchWaitingTransfers(): Promise<WaitingTransferItem[]> {
    const res = await fetch(`${API_BASE}/me/waiting-transfers`);
    return handle<WaitingTransferItem[]>(res);
}

export async function fetchTransferDetails(
    id: number,
): Promise<TransferDetails> {
    const res = await fetch(`${API_BASE}/transfers/${id}`);
    return handle<TransferDetails>(res);
}

export async function confirmAuthorization(
    payload: AuthorizePaymentRequest,
): Promise<AuthorizePaymentResult> {
    const res = await fetch(`${API_BASE}/authorizations/confirm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    return handle<AuthorizePaymentResult>(res);
}
