// src/NewPaymentPage.tsx

import { useEffect, useState } from 'react';
import './App.css';
import {
    getMyAccounts,
    createPayment,
    type AccountSummary,
    type NewPaymentRequest,
    type NewPaymentResult,
    isApiError,
    isUnderReview,
    mapPaymentError,
} from './api';
import { formatMoney, parseAmount } from './money';

const MAX_MESSAGE_LENGTH = 140;

/**
 * The locale to read an ambiguous amount in.
 *
 * Only `1,234` needs it - the one string that is a valid number under both the Czech and the
 * English convention and means two different things. Everything the customer sees is Czech
 * whatever this returns.
 */
function readerLocale(): string {
    return navigator.language || 'cs-CZ';
}

type InfoState =
    | { type: 'none' }
    | { type: 'success'; result: NewPaymentResult }
    | { type: 'error'; messages: string[] };

interface Props {
    onNavigate: (view: 'new-payment' | 'waiting-auth' | 'fraud-desk') => void;
}

export default function NewPaymentPage({ onNavigate }: Props) {
    const [accounts, setAccounts] = useState<AccountSummary[]>([]);
    const [selectedAccountId, setSelectedAccountId] = useState<number | null>(null);

    const [targetIban, setTargetIban] = useState('');
    const [amount, setAmount] = useState('');
    const [message, setMessage] = useState('');

    const [loadingAccounts, setLoadingAccounts] = useState(true);
    const [accountsError, setAccountsError] = useState<string | null>(null);

    const [submitting, setSubmitting] = useState(false);
    const [info, setInfo] = useState<InfoState>({ type: 'none' });

    // Load accounts when the component is mounted
    useEffect(() => {
        let cancelled = false;

        async function load() {
            try {
                setLoadingAccounts(true);
                setAccountsError(null);
                const data = await getMyAccounts();
                if (cancelled) return;
                setAccounts(data);
                if (data.length > 0) {
                    setSelectedAccountId(data[0].id);
                }
            } catch (e) {
                if (cancelled) return;
                setAccountsError((e as Error).message);
            } finally {
                if (!cancelled) setLoadingAccounts(false);
            }
        }

        load();
        return () => {
            cancelled = true;
        };
    }, []);

    const selectedAccount = selectedAccountId != null
        ? accounts.find((a) => a.id === selectedAccountId) ?? null
        : null;

    // Submit payment
    async function handleSendClick(e: React.FormEvent) {
        e.preventDefault();
        setInfo({ type: 'none' });

        // Validate selected account
        if (selectedAccountId == null) {
            setInfo({
                type: 'error',
                messages: ['Please select source account.'],
            });
            return;
        }

        // Parse amount. The reason is shown as given: it names what is wrong with this string,
        // which one generic "invalid amount" cannot, and the amounts people get wrong are the
        // ones where the difference between two readings is a factor of a thousand.
        const parsed = parseAmount(amount, readerLocale());
        if (!parsed.ok) {
            setInfo({ type: 'error', messages: [parsed.reason] });
            return;
        }
        const amountValue = parsed.value;

        // Basic IBAN check
        if (!targetIban.trim()) {
            setInfo({
                type: 'error',
                messages: ['Target IBAN is required.'],
            });
            return;
        }

        const payload: NewPaymentRequest = {
            sourceAccountId: selectedAccountId,
            targetIban: targetIban.trim(),
            amountCzk: amountValue,
            message: message.trim(),
        };

        try {
            setSubmitting(true);
            const result = await createPayment(payload);
            setInfo({ type: 'success', result });
        } catch (e) {
            if (isApiError(e)) {
                setInfo({
                    type: 'error',
                    messages: mapPaymentError(e),
                });
            } else {
                setInfo({
                    type: 'error',
                    messages: ['Unexpected error. Please try again later.'],
                });
            }
        } finally {
            setSubmitting(false);
        }
    }

    // Render UI
    return (
        <div className="app-shell">
            <div className="card">
                <header className="card-header">
                    <h1>New Payment</h1>
                </header>

                <div className="card-body layout">
                    {/* Navigation for customer views */}
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

                    {/* Main payment form */}
                    <main className="form-panel">
                        <h2>Form – New payment</h2>

                        {loadingAccounts && <p>Loading accounts…</p>}
                        {accountsError && (
                            <p style={{ color: 'salmon' }}>Error: {accountsError}</p>
                        )}

                        {!loadingAccounts && !accountsError && accounts.length === 0 && (
                            <p>No accounts available.</p>
                        )}

                        {accounts.length > 0 && (
                            <form className="form" onSubmit={handleSendClick}>
                                {/* From account */}
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
                                        Balance: {formatMoney(selectedAccount?.balance)}
                                    </div>
                                </div>

                                {/* Target IBAN + amount */}
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
                                        {/*
                                          Stays type="text". A Czech amount is written
                                          `1 500,00`, which type="number" refuses outright -
                                          it reports .value as the empty string for anything
                                          it cannot interpret, so the field would go blank
                                          on a perfectly good amount.

                                          Normalized on blur rather than on every keystroke:
                                          rewriting while someone is still typing moves the
                                          caret out from under them, and half an amount is
                                          not yet an amount.
                                        */}
                                        <input
                                            className="amount-input"
                                            type="text"
                                            inputMode="decimal"
                                            placeholder="0,00"
                                            value={amount}
                                            onChange={(e) => setAmount(e.target.value)}
                                            onBlur={() => {
                                                const parsed = parseAmount(amount, readerLocale());
                                                if (parsed.ok) {
                                                    setAmount(parsed.czech);
                                                }
                                            }}
                                        />
                                    </div>
                                </div>

                                {/* Message for recipient */}
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
                                    <div className="summary" style={{ borderColor: 'salmon' }}>
                                        <div className="summary-title">We could not send this payment</div>
                                        <ul>
                                            {info.messages.map((m, idx) => (
                                                <li key={idx}>{m}</li>
                                            ))}
                                        </ul>
                                    </div>
                                )}

                                {info.type === 'success' && (
                                    <div className="summary">
                                        <div className="summary-title">
                                            {isUnderReview(info.result.status)
                                                ? 'The bank is reviewing this payment'
                                                : info.result.authorizationRequired
                                                    ? 'Authorization will be required'
                                                    : 'Confirmation'}
                                        </div>
                                        {isUnderReview(info.result.status) && (
                                            <p className="helper-text">
                                                Nothing has been taken from your account. You
                                                will be able to confirm this payment once the
                                                review is finished, and you can cancel it at any
                                                time from Waiting authorizations.
                                            </p>
                                        )}
                                        <ul>
                                            <li>Transfer ID: {info.result.transferId}</li>
                                            <li>Status: {info.result.status}</li>

                                            {/*
                                              "Charged", not "Amount requested". This field is
                                              amount plus fee - PaymentController sets it that
                                              way and NewPaymentResultDto says so - and the fee
                                              is printed on the next line, so the old label
                                              invited the reader to add the two and arrive at
                                              the amount plus twice the fee. Waiting
                                              authorizations already labels the same field
                                              "Charged"; the two money screens now agree.
                                            */}
                                            <li>
                                                Charged: {formatMoney(info.result.chargedAmount)}
                                            </li>
                                            <li>
                                                Fee: {formatMoney(info.result.feeAmount)}
                                            </li>
                                            <li>
                                                {info.result.authorizationRequired
                                                    ? <>Current balance: {formatMoney(info.result.newBalance)}</>
                                                    : <>New balance: {formatMoney(info.result.newBalance)}</>}
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
    );
}
