// src/api.ts

//TODO: I have client.ts and this api.ts, it seems that some functions
// are the same in both files, I think that maybe I should get rid of
// one of the file and use a complete logic in one of these files for
// both UC04 and UC05
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
    fromIban: string;
    fromBalance: string;
    toIban: string;
    amount: string;
    feeAmount: string;
    status: string;
    createdAt: string;
    authMethod?: string;
}

export interface AuthorizePaymentRequest {
    transferId: number;
    otp: string;
}

export interface AuthorizePaymentResult {
    transferId: number;
    status: string;
    chargedAmount?: string | null;
    newBalance?: string | null;
    declineReason?: string | null;
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
    const res = await fetch(
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

