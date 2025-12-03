// src/pages/NewPaymentPage.tsx
import { useEffect, useState } from 'react'
import './App.css'
import {
    getMyAccounts,
    createPayment,
    type AccountSummary,
    type NewPaymentResultDto,
} from './api/client.ts'

const MAX_MESSAGE_LENGTH = 140

type InfoState =
    | { type: 'none' }
    | { type: 'success'; result: NewPaymentResultDto }
    | { type: 'error'; message: string }

interface Props {
    onNavigate: (view: 'new-payment' | 'waiting-auth') => void;
}
//TODO: change Authorization will be required / Confirmation header so it will only show "Authorization will be required" or "Confirmation"
//TODO: add charged and fee
export default function NewPaymentPage({ onNavigate }: Props) {
    const [accounts, setAccounts] = useState<AccountSummary[]>([])
    const [selectedAccountId, setSelectedAccountId] = useState<number | null>(
        null,
    )

    const [targetIban, setTargetIban] = useState('')
    const [amount, setAmount] = useState('')
    const [message, setMessage] = useState('')

    const [loadingAccounts, setLoadingAccounts] = useState(true)
    const [accountsError, setAccountsError] = useState<string | null>(null)

    const [submitting, setSubmitting] = useState(false)
    const [info, setInfo] = useState<InfoState>({ type: 'none' })

    // --- загрузка счетов при монтировании ---

    useEffect(() => {
        let cancelled = false

        async function load() {
            try {
                setLoadingAccounts(true)
                setAccountsError(null)
                const data = await getMyAccounts()
                if (cancelled) return
                setAccounts(data)
                if (data.length > 0) {
                    setSelectedAccountId(data[0].id)
                }
            } catch (e) {
                if (cancelled) return
                setAccountsError((e as Error).message)
            } finally {
                if (!cancelled) setLoadingAccounts(false)
            }
        }

        load()
        return () => {
            cancelled = true
        }
    }, [])

    const selectedAccount = accounts.find((a) => a.id === selectedAccountId ?? -1)

    // --- отправка платежа ---

    async function handleSendClick(e: React.FormEvent) {
        e.preventDefault()
        setInfo({ type: 'none' })

        if (!selectedAccountId) {
            setInfo({ type: 'error', message: 'Please select source account.' })
            return
        }

        const amountValue = Number(
            amount.replace(/\s+/g, '').replace(',', '.'),
        )
        if (!Number.isFinite(amountValue) || amountValue <= 0) {
            setInfo({ type: 'error', message: 'Please enter a valid amount.' })
            return
        }

        if (!targetIban.trim()) {
            setInfo({ type: 'error', message: 'Target IBAN is required.' })
            return
        }

        try {
            setSubmitting(true)
            const result = await createPayment({
                customerId: 2, // у тебя в data.json первый "живой" customer = 2
                sourceAccountId: selectedAccountId,
                targetIban: targetIban.trim(),
                amountCzk: amountValue,
                message: message.trim(),
            })
            setInfo({ type: 'success', result })
        } catch (e) {
            setInfo({ type: 'error', message: (e as Error).message })
        } finally {
            setSubmitting(false)
        }
    }

    // --- UI ---

    return (
        <div className="app-shell">
            <div className="card">
                <header className="card-header">
                    <h1>New Payment</h1>
                </header>

                <div className="card-body layout">
                    {/* Navigation */}
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
                                    className="nav-link nav-link--active"
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
                                    className="nav-link"
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


                    {/* Form */}
                    <main className="form-panel">
                        <h2>Form – New payment</h2>

                        {loadingAccounts && <p>Loading accounts…</p>}
                        {accountsError && (
                            <p style={{color: 'salmon'}}>Error: {accountsError}</p>
                        )}

                        {!loadingAccounts && !accountsError && accounts.length === 0 && (
                            <p>No accounts available.</p>
                        )}

                        {accounts.length > 0 && (
                            <form className="form" onSubmit={handleSendClick}>
                                {/* From */}
                                <div className="field-row">
                                    <label className="field-label">From:</label>
                                    <select
                                        className="field-input"
                                        value={selectedAccountId ?? ''}
                                        onChange={(e) =>
                                            setSelectedAccountId(Number(e.target.value))
                                        }
                                    >
                                        {accounts.map((acc) => (
                                            <option key={acc.id} value={acc.id}>
                                                {acc.iban}
                                            </option>
                                        ))}
                                    </select>
                                    <div className="field-side">
                                        Balance: {selectedAccount?.balance ?? '—'}
                                    </div>
                                </div>

                                {/* To + Amount */}
                                <div className="field-row">
                                    <label className="field-label">To:</label>
                                    <input
                                        className="field-input"
                                        type="text"
                                        placeholder="IBAN"
                                        value={targetIban}
                                        onChange={(e) => setTargetIban(e.target.value)}
                                    />
                                    <div className="field-side">
                                        Amount:
                                        <input
                                            className="amount-input"
                                            type="text"
                                            placeholder="0.00 CZK"
                                            value={amount}
                                            onChange={(e) => setAmount(e.target.value)}
                                        />
                                    </div>
                                </div>

                                {/* Message */}
                                <div className="field-column">
                                    <label className="field-label">
                                        Message for recipient:
                                    </label>
                                    <textarea
                                        className="textarea"
                                        rows={3}
                                        maxLength={MAX_MESSAGE_LENGTH}
                                        value={message}
                                        onChange={(e) => setMessage(e.target.value)}
                                    />
                                    <div className="message-counter">
                                        {message.length}/{MAX_MESSAGE_LENGTH}
                                    </div>
                                </div>

                                {/* Result / errors */}
                                {info.type === 'error' && (
                                    <div className="summary" style={{borderColor: 'salmon'}}>
                                        <div className="summary-title">Error</div>
                                        <ul>
                                            <li>{info.message}</li>
                                        </ul>
                                    </div>
                                )}

                                {info.type === 'success' && (
                                    <div className="summary">
                                        <div className="summary-title">
                                            {info.result.authorizationRequired
                                                ? 'Authorization will be required'
                                                : 'Confirmation'}
                                        </div>
                                        <ul>
                                            <li>Transfer ID: {info.result.transferId}</li>
                                            <li>Status: {info.result.status}</li>
                                            <li>
                                                {info.result.authorizationRequired
                                                    ? <>Amount: {info.result.chargedAmount}</>    // ещё НЕ списано
                                                    : <>Charged: {info.result.chargedAmount}</>}
                                            </li>
                                            <li>
                                                {info.result.authorizationRequired
                                                    ? <>Current balance: {info.result.newBalance}</>
                                                    : <>New balance: {info.result.newBalance}</>}
                                            </li>
                                            <li>
                                                Authorization required:{' '}
                                                {info.result.authorizationRequired ? 'YES' : 'NO'}
                                            </li>
                                        </ul>
                                    </div>
                                )}



                                <div className="actions">
                                    <button
                                        type="submit"
                                        className="btn-primary"
                                        disabled={submitting}
                                    >
                                        {submitting ? 'Sending…' : 'Send now'}
                                    </button>
                                    <button
                                        type="button"
                                        className="btn-secondary"
                                        disabled
                                        title="Not implemented yet"
                                    >
                                        Save as draft
                                    </button>
                                </div>
                            </form>
                        )}
                    </main>
                </div>
            </div>
        </div>
    )
}
