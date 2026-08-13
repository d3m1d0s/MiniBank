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

            // The fields have done their job; a form that keeps them re-sends the
            // same payment on one stray Enter. The confirmation panel stays.
            setTargetIban('');
            setAmount('');
            setMessage('');

            // The payment may have moved money, so the balances fetched on mount
            // are stale next to the confirmation's new one.
            try {
                setAccounts(await getMyAccounts());
            } catch {
                // The payment itself succeeded; if this refresh fails, the
                // confirmation still shows the authoritative new balance.
            }
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
                    {/*
                      Navigation for customer views. Entries without a screen stay
                      to show the product this page lives in, but are marked
                      planned: muted, inert, and saying so in the title.
                    */}
                    <nav className="nav">
                        <div className="nav-title">Navigation</div>
                        <ul>
                            <li>
                                <button
                                    type="button"
                                    className="nav-link planned-item"
                                    aria-disabled="true"
                                    title="Planned - not part of this showcase"
                                >
                                    Dashboard
                                </button>
                            </li>
                            <li>
                                <button
                                    type="button"
                                    className="nav-link planned-item"
                                    aria-disabled="true"
                                    title="Planned - not part of this showcase"
                                >
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
                                <button
                                    type="button"
                                    className="nav-link planned-item"
                                    aria-disabled="true"
                                    title="Planned - not part of this showcase"
                                >
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
                                <button
                                    type="button"
                                    className="nav-link planned-item"
                                    aria-disabled="true"
                                    title="Planned - not part of this showcase"
                                >
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
                                    <label className="field-label" htmlFor="payment-source">
                                        From:
                                    </label>
                                    <select
                                        id="payment-source"
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
                                    <label className="field-label" htmlFor="payment-target">
                                        To:
                                    </label>
                                    <input
                                        id="payment-target"
                                        className="field-input"
                                        type="text"
                                        placeholder="IBAN"
                                        value={targetIban}
                                        onChange={(e) => setTargetIban(e.target.value)}
                                    />
                                    <label className="field-side" htmlFor="payment-amount">
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
                                            id="payment-amount"
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
                                    </label>
                                </div>

                                {/* Message for recipient */}
                                <div className="field-column">
                                    <label className="field-label" htmlFor="payment-message">
                                        Message for recipient:
                                    </label>
                                    <textarea
                                        id="payment-message"
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
                                    <div className="summary" role="alert" style={{ borderColor: 'salmon' }}>
                                        <div className="summary-title">We could not send this payment</div>
                                        <ul>
                                            {info.messages.map((m, idx) => (
                                                <li key={idx}>{m}</li>
                                            ))}
                                        </ul>
                                    </div>
                                )}

                                {info.type === 'success' && (
                                    <div className="summary" role="status">
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
                                              chargedAmount is amount plus fee for every
                                              outcome, even ones where no money has moved:
                                              authorizationRequired=true means nothing was
                                              debited yet. So the label may only claim a
                                              charge once the payment has settled; until
                                              then it states what will be taken. The fee is
                                              printed on the next line, so the label must
                                              not read as "amount requested" either - that
                                              invited adding the two and arriving at the
                                              amount plus twice the fee. Waiting
                                              authorizations labels the settled field
                                              "Charged"; the settled branch here agrees.
                                            */}
                                            <li>
                                                {info.result.authorizationRequired
                                                    ? <>Will be charged after confirmation: {formatMoney(info.result.chargedAmount)}</>
                                                    : <>Charged: {formatMoney(info.result.chargedAmount)}</>}
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
                                        className="btn-secondary planned-item"
                                        aria-disabled="true"
                                        title="Planned - not part of this showcase"
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
