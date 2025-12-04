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

// src/api.ts

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

export async function cancelTransfer(
    id: number,
): Promise<AuthorizePaymentResult> {
    const res = await fetch(`${API_BASE}/transfers/${id}/cancel`, {
        method: 'POST',
    });
    return handle<AuthorizePaymentResult>(res);
}
