// src/WaitingAuthorizationsPage.tsx
import { useEffect, useState } from 'react'
import './App.css'
import {
    fetchWaitingTransfers,
    fetchTransferDetails,
    confirmAuthorization,
    type WaitingTransferItem,
    type TransferDetails,
    type AuthorizePaymentResult, cancelTransfer,
} from './api'

interface Props {
    onNavigate: (view: 'new-payment' | 'waiting-auth' | 'fraud-desk') => void;
}

function mapDeclineReason(reason: string): string {
    const r = reason.toLowerCase();

    if (r.includes('otp failed') || r.includes('wrong otp')) {
        return 'Wrong one-time password (OTP). Please check the code and try again.';
    }

    if (r.includes('too many') || r.includes('attempts exceeded')) {
        return 'Too many incorrect OTP attempts - this transfer was declined for security reasons.';
    }

    if (r.includes('expired') || r.includes('authorization window')) {
        return 'Authorization time window has expired. Please create a new transfer if you still want to send money.';
    }

    if (r.includes('insufficient funds')) {
        return 'Insufficient balance - top up your account or cancel this transfer.';
    }

    if (r.includes('canceled by customer') || r.includes('cancelled by customer') || r.includes('canceled')) {
        return 'The transfer was canceled by the customer.';
    }

    return reason;
}


export function WaitingAuthorizationsPage({ onNavigate }: Props) {
    const [items, setItems] = useState<WaitingTransferItem[]>([])
    const [selectedId, setSelectedId] = useState<number | null>(null)
    const [details, setDetails] = useState<TransferDetails | null>(null)
    const [otp, setOtp] = useState('')
    const [result, setResult] = useState<AuthorizePaymentResult | null>(null)

    // 🔹 Разделяем ошибки: одна для списка/деталей, другая для подтверждения
    const [listError, setListError] = useState<string | null>(null)
    const [confirmError, setConfirmError] = useState<string | null>(null)

    const [loading, setLoading] = useState(false)

    useEffect(() => {
        void loadList()
    }, [])

    async function loadList() {
        try {
            // ошибки списка не должны влиять на ошибки подтверждения
            setListError(null)
            const data = await fetchWaitingTransfers()
            setItems(data)
            // если выбранный перевод пропал — сбросить выбор
            if (selectedId && !data.some((x) => x.id === selectedId)) {
                setSelectedId(null)
                setDetails(null)
            }
        } catch (e) {
            setListError((e as Error).message)
        }
    }

    async function handleSelect(id: number) {
        setSelectedId(id)
        setResult(null)
        try {
            setListError(null)
            const d = await fetchTransferDetails(id)
            setDetails(d)
        } catch (e) {
            setListError((e as Error).message)
        }
    }

    // src/WaitingAuthorizationsPage.tsx

    async function handleConfirm() {
        if (!selectedId || !otp) return

        try {
            setLoading(true)
            setConfirmError(null)

            // 1) Пытаемся авторизовать
            const res = await confirmAuthorization({ transferId: selectedId, otp })
            setResult(res)
            setOtp('')

            // 2) Обновляем список (уйдут переводы, которые уже не WAITING_AUTH)
            await loadList()

            // 3) Подтягиваем свежие детали, чтобы видеть обновлённые triesLeft/authValidUntil
            let freshDetails: TransferDetails | null = null
            try {
                const d = await fetchTransferDetails(selectedId)
                setDetails(d)
                freshDetails = d
            } catch {
                // если деталей уже нет – просто игнорируем
            }

            // 4) Разбор результата и установка человекочитаемого сообщения

            if (res.status === 'SENT') {
                // Успешная авторизация – ошибок нет
                setConfirmError(null)
            } else if (res.status === 'DECLINED') {
                // Окончательный отказ – используем declineReason с маппингом
                if (res.declineReason) {
                    setConfirmError(mapDeclineReason(res.declineReason))
                } else {
                    setConfirmError('Authorization was declined.')
                }
            } else if (res.status === 'WAITING_AUTH') {
                // ❗ Кейс неверного OTP: статус всё ещё WAITING_AUTH,
                // но счётчик попыток уменьшился.

                const tries =
                    freshDetails?.triesLeft ??
                    details?.triesLeft

                const extra =
                    tries !== undefined && tries !== null
                        ? ` You have ${tries} attempt${tries === 1 ? '' : 's'} left.`
                        : ''

                setConfirmError(
                    'Wrong one-time password (OTP). Please check the code and try again.' +
                    extra,
                )
            } else {
                // На всякий случай – прочие статусы
                setConfirmError(null)
            }
        } catch (e) {
            const err = e as Error
            let code: string | undefined
            let msg = err.message ?? ''

            // Попробуем распарсить JSON {"code":"...","message":"..."}
            const trimmed = msg.trim()
            if (trimmed.startsWith('{')) {
                try {
                    const parsed = JSON.parse(trimmed) as { code?: string; message?: string }
                    code = parsed.code
                    msg = parsed.message || msg
                } catch {
                    // не JSON – оставляем как есть
                }
            }

            const lower = msg.toLowerCase()

            // Недостаточно средств
            if (code === 'INSUFFICIENT_FUNDS' || lower.includes('insufficient funds')) {
                setConfirmError(
                    'Insufficient balance – top up your account and try again or cancel this transfer.',
                )
            }
            // Неверный OTP (если когда-нибудь начнём слать это ошибкой с бэка)
            else if (
                code === 'WRONG_OTP' ||
                lower.includes('otp failed') ||
                lower.includes('wrong otp')
            ) {
                setConfirmError(
                    'Wrong one-time password (OTP). Please check the code and try again.',
                )
            }
            // Слишком много попыток (как ошибка)
            else if (
                code === 'OTP_ATTEMPTS_EXCEEDED' ||
                lower.includes('too many attempts') ||
                lower.includes('attempts exceeded')
            ) {
                setConfirmError(
                    'Too many incorrect OTP attempts – this transfer was declined for security reasons.',
                )
            }
            // Истёк срок действия (как ошибка)
            else if (
                code === 'OTP_EXPIRED' ||
                lower.includes('expired') ||
                lower.includes('authorization window')
            ) {
                setConfirmError(
                    'Authorization time window has expired – this transfer can no longer be confirmed.',
                )
            } else {
                setConfirmError(msg || 'Authorization failed.')
            }
        } finally {
            setLoading(false)
        }
    }





    async function handleCancel() {
        if (!selectedId) return

        try {
            setLoading(true)
            setConfirmError(null)

            const res = await cancelTransfer(selectedId)
            setResult(res)

            // после отмены перевод исчезнет из списка WAITING_AUTH
            await loadList()

            // детали больше не так важны, но можно попытаться обновить
            try {
                const d = await fetchTransferDetails(selectedId)
                setDetails(d)
            } catch {
                setDetails(null)
            }
        } catch (e) {
            const err = e as Error
            setConfirmError(err.message || 'Failed to cancel transfer.')
        } finally {
            setLoading(false)
        }
    }




    return (
        <div className="app-shell">
            <div className="card">
                <header className="card-header">
                    <h1>Authorize Payment</h1>
                </header>

                <div className="card-body layout">
                    {/* Навигация слева */}
                    <nav className="nav">
                        <div className="nav-title">Navigation</div>
                        <ul>
                            <li>
                                <button type="button" className="nav-link">
                                    Dashboard
                                </button>
                            </li>
                            <li>
                                <button type="button" className="nav-link">
                                    Accounts
                                </button>
                            </li>
                            <li>
                                <button
                                    type="button"
                                    className="nav-link"
                                    onClick={() => onNavigate('new-payment')}
                                >
                                    New payment
                                </button>
                            </li>
                            <li>
                                <button type="button" className="nav-link">
                                    History & Statements
                                </button>
                            </li>
                            <li>
                                <button
                                    type="button"
                                    className="nav-link nav-link--active"
                                    onClick={() => onNavigate('waiting-auth')}
                                >
                                    Waiting authorizations
                                </button>
                            </li>
                            <li>
                                <button type="button" className="nav-link">
                                    Settings
                                </button>
                            </li>
                        </ul>
                    </nav>

                    {/* Правая часть: таблица + детали + подтверждение */}
                    <main className="form-panel">
                        {/* Секция: список ожидающих переводов */}
                        <section className="section">
                            <h2 className="section-title">Waiting transfers</h2>

                            {/* 🔹 Ошибки, связанные со списком / деталями */}
                            {listError && (
                                <div
                                    className="summary"
                                    style={{ borderColor: 'salmon', marginBottom: 8 }}
                                >
                                    <div className="summary-title">Error</div>
                                    <ul>
                                        <li>{listError}</li>
                                    </ul>
                                </div>
                            )}

                            {items.length === 0 ? (
                                <p className="helper-text">No waiting transfers.</p>
                            ) : (
                                <div className="table-wrapper">
                                    <table className="table">
                                        <thead>
                                        <tr>
                                            <th>ID</th>
                                            <th>Beneficiary IBAN</th>
                                            <th>Amount</th>
                                            <th>Created</th>
                                            <th>Auth</th>
                                        </tr>
                                        </thead>
                                        <tbody>
                                        {items.map((it) => (
                                            <tr
                                                key={it.id}
                                                onClick={() => handleSelect(it.id)}
                                                className={
                                                    selectedId === it.id ? 'table-row--selected' : ''
                                                }
                                            >
                                                <td>{it.id}</td>
                                                <td>{it.targetIban || (it as any).beneficiaryIban}</td>
                                                <td>{it.amount}</td>
                                                <td>
                                                    {it.createdAt
                                                        ? new Date(it.createdAt).toLocaleString()
                                                        : ''}
                                                </td>
                                                <td>{it.authMethod}</td>
                                            </tr>
                                        ))}
                                        </tbody>
                                    </table>
                                </div>
                            )}
                        </section>

                        {/* Секция: детали выбранного перевода */}
                        <section className="section">
                            <h2 className="section-title">Selected transfer details</h2>
                            <div className="section-block">
                                {details ? (
                                    <div>
                                        <p>
                                            <strong>From:</strong> {details.fromIban}{' '}
                                            {details.fromBalance &&
                                                `(Balance: ${details.fromBalance})`}
                                        </p>
                                        <p>
                                            <strong>To:</strong> {details.toIban}</p>
                                        <p>
                                            <strong>Amount:</strong> {details.amount}
                                        </p>
                                        <p>
                                            <strong>Fee:</strong> {details.feeAmount}</p>
                                        <p>
                                            <strong>Created:</strong>{' '}
                                            {details.createdAt
                                                ? new Date(details.createdAt).toLocaleString()
                                                : ''}
                                        </p>
                                        <p>
                                            <strong>Status:</strong> {details.status}</p>
                                        <p>
                                            <strong>Auth method:</strong> {details.authMethod || '—'}
                                        </p>
                                    </div>
                                ) : (
                                    <p className="helper-text">No transfer selected.</p>
                                )}
                            </div>
                        </section>

                        {/* Секция: подтверждение OTP + результат */}
                        <section className="section">
                            <h2 className="section-title">Confirm authorization</h2>
                            <div className="section-block inline">
                                <input
                                    className="otp-input"
                                    type="text"
                                    value={otp}
                                    onChange={(e) => setOtp(e.target.value)}
                                    placeholder="Enter OTP"
                                    maxLength={10}
                                />
                                <button
                                    type="button"
                                    className="btn-primary"
                                    onClick={handleConfirm}
                                    disabled={!selectedId || !otp || loading}
                                >
                                    {loading ? 'Confirming…' : 'Confirm'}
                                </button>

                                <button
                                    type="button"
                                    className="btn-secondary"
                                    onClick={handleCancel}
                                    disabled={!selectedId || loading}
                                    style={{marginLeft: 8}}
                                >
                                    Cancel transfer
                                </button>
                            </div>

                            <p className="helper-text">
                                <p>
                                    <strong>Tries left:</strong>{' '}
                                    {details?.triesLeft ?? '—'}
                                </p>
                                <p>
                                    <strong>Will be expired after:</strong>{' '}
                                    {details?.authValidUntil
                                        ? new Date(details.authValidUntil).toLocaleString()
                                        : '—'}
                                </p>
                            </p>

                            {/* Ошибки именно подтверждения авторизации */}
                            {confirmError && (
                                <div
                                    className="summary"
                                    style={{borderColor: 'salmon', marginTop: 8}}
                                >
                                    <div className="summary-title">Error</div>
                                    <ul>
                                        <li>{confirmError}</li>
                                    </ul>
                                </div>
                            )}

                            {result && (
                                <div className="summary" style={{marginTop: 10}}>
                                    <div className="summary-title">
                                        {result.status === 'SENT'
                                            ? 'Payment authorized'
                                            : 'Authorization result'}
                                    </div>
                                    <ul>
                                        <li>Transfer ID: {result.transferId}</li>
                                        <li>Status: {result.status}</li>

                                        {/* Показываем Charged только если реально что-то списали */}
                                        {result.chargedAmount && (
                                            <li>Charged: {result.chargedAmount}</li>
                                        )}

                                        {/* Баланс всегда актуальный, но подпись можно различать */}
                                        <li>
                                            {result.status === 'SENT' ? 'New balance: ' : 'Current balance: '}
                                            {result.newBalance}
                                        </li>

                                        {/* Причина отказа, если есть */}
                                        {result.declineReason && (
                                            <li>Reason: {mapDeclineReason(result.declineReason)}</li>
                                        )}
                                    </ul>
                                </div>
                            )}

                        </section>
                    </main>
                </div>
            </div>
        </div>
    )
}
