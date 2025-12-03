// src/api/client.ts

const API_BASE = '/api'

// --- DTO, синхронизированные с твоими Java-record'ами ---

export type AccountSummary = {
    id: number
    iban: string
    balance: string
}

export type NewPaymentRequestDto = {
    customerId?: number | null
    sourceAccountId: number
    targetIban: string
    amountCzk: number
    message: string
}

export type NewPaymentResultDto = {
    transferId: number
    status: string
    chargedAmount: string
    feeAmount: string
    newBalance: string
    authorizationRequired: boolean
}

type ApiErrorBody = {
    code?: string
    message?: string
}

// --- Вспомогательный обработчик ответа ---

async function handleResponse<T>(res: Response): Promise<T> {
    if (!res.ok) {
        let body: ApiErrorBody | undefined
        try {
            body = (await res.json()) as ApiErrorBody
        } catch {
            // тело не JSON — игнорируем
        }
        const code = body?.code ?? `HTTP_${res.status}`
        const msg = body?.message ?? res.statusText
        throw new Error(`${code}: ${msg}`)
    }

    return (await res.json()) as T
}

// --- Публичные функции API ---

export async function getMyAccounts(): Promise<AccountSummary[]> {
    const res = await fetch(`${API_BASE}/me/accounts`)
    return handleResponse<AccountSummary[]>(res)
}

export async function createPayment(
    payload: NewPaymentRequestDto,
): Promise<NewPaymentResultDto> {
    const res = await fetch(`${API_BASE}/payments`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    })
    return handleResponse<NewPaymentResultDto>(res)
}
