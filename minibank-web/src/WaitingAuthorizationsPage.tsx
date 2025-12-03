// src/WaitingAuthorizationsPage.tsx
import { useEffect, useState } from 'react'
import './App.css'
import {
    fetchWaitingTransfers,
    fetchTransferDetails,
    confirmAuthorization,
    type WaitingTransferItem,
    type TransferDetails,
    type AuthorizePaymentResult,
} from './api'

interface Props {
    onNavigate: (view: 'new-payment' | 'waiting-auth') => void
}

function mapDeclineReason(reason: string): string {
    if (reason === 'OTP failed') return 'Wrong code.';
    if (reason === 'Insufficient funds') {
        return 'Insufficient balance – top up your account or cancel this transfer.';
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

    async function handleConfirm() {
        if (!selectedId || !otp) return
        try {
            setLoading(true)
            // сбрасываем именно ошибку подтверждения
            setConfirmError(null)
            const res = await confirmAuthorization({ transferId: selectedId, otp })
            setResult(res)
            setOtp('')
            await loadList()
        } catch (e) {
            setConfirmError((e as Error).message)
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
                            </div>

                            {/* 🔹 Ошибки именно подтверждения авторизации */}
                            {confirmError && (
                                <div
                                    className="summary"
                                    style={{ borderColor: 'salmon', marginTop: 8 }}
                                >
                                    <div className="summary-title">Error</div>
                                    <ul>
                                        <li>{confirmError}</li>
                                    </ul>
                                </div>
                            )}

                            {result && (
                                <div className="summary" style={{ marginTop: 10 }}>
                                    <div className="summary-title">Result</div>
                                    <ul>
                                        <li>
                                            Transfer ID:{' '}
                                            {result.transferId ?? details?.id ?? selectedId ?? '—'}
                                        </li>

                                        <li>Status: {result.status}</li>

                                        {/* Показываем Charged только если реально отправили платёж */}
                                        {result.status === 'SENT' && (
                                            <li>Charged: {result.chargedAmount ?? '—'}</li>
                                        )}

                                        <li>
                                            {result.status === 'SENT' ? 'New balance' : 'Current balance'}:{' '}
                                            {result.newBalance ?? '—'}
                                        </li>

                                        {/* Сообщение для DECLINED/ошибок */}
                                        {result.declineReason && (
                                            <li>Message: {mapDeclineReason(result.declineReason)}</li>
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
