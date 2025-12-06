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
