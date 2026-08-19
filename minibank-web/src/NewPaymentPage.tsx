// src/NewPaymentPage.tsx

import { useEffect, useState, type ReactNode } from 'react';
import './App.css';
import {
    getMyAccounts,
    createPayment,
    fetchMyBeneficiaries,
    type AccountSummary,
    type Beneficiary,
    type NewPaymentRequest,
    type NewPaymentResult,
    isUnderReview,
} from './api';
import { formatMoney, readerLocale } from './money';
import { parseAmount } from '@shared/money';
import { describeApiError, describeApiErrorLines } from '@shared/apiErrors';
import { transferStatusLabel, transferStatusTone } from '@shared/glossary';
import { formatIban, formatTransferId } from '@shared/format';
import Nav from './Nav';
import type { NavRole, NavView } from '@shared/navigation';

const MAX_MESSAGE_LENGTH = 140;

type InfoState =
    | { type: 'none' }
    | { type: 'success'; result: NewPaymentResult }
    | { type: 'error'; messages: string[] };

interface Props {
    role: NavRole;
    /* The mark and the name of the application, built by App and rendered here as it arrives. */
    brand?: ReactNode;
    /* Who is signed in and the way out, built by App and rendered here as it arrives. */
    identity?: ReactNode;
    onNavigate: (view: NavView) => void;
}

export default function NewPaymentPage({ role, brand, identity, onNavigate }: Props) {
    const [accounts, setAccounts] = useState<AccountSummary[]>([]);
    const [selectedAccountId, setSelectedAccountId] = useState<number | null>(null);

    const [targetIban, setTargetIban] = useState('');
    const [amount, setAmount] = useState('');
    const [message, setMessage] = useState('');

    /**
     * The customer's saved payees, and which one is chosen.
     *
     * Two ways to name a destination and both stay: choosing a name sends its id, which is what
     * reaches the trusted branch of the risk rules, and typing an account number sends the number,
     * which has no payee behind it and never can. Last touched wins, so editing the field below
     * clears the choice; there is no control for undoing a choice because the field is one.
     *
     * The list failing is not the form failing, so it has a flag of its own rather than sharing
     * the accounts error, which kills the form: a payment can still be made by typing an IBAN.
     */
    const [beneficiaries, setBeneficiaries] = useState<Beneficiary[]>([]);
    const [beneficiaryId, setBeneficiaryId] = useState<number | null>(null);
    const [beneficiariesFailed, setBeneficiariesFailed] = useState(false);

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
                setAccountsError(describeApiError(e, 'accounts'));
            } finally {
                if (!cancelled) setLoadingAccounts(false);
            }
        }

        /**
         * The address book, fetched beside the accounts and gating nothing.
         *
         * A slow list of payees must not delay a payment, so this has no loading line of its own:
         * the row simply is not there until the names arrive, and "Loading accounts…" stays the
         * form's only such sentence.
         */
        async function loadBeneficiaries() {
            try {
                const saved = await fetchMyBeneficiaries();
                if (cancelled) return;
                setBeneficiaries(saved);
                setBeneficiariesFailed(false);
            } catch {
                if (cancelled) return;
                setBeneficiaries([]);
                setBeneficiariesFailed(true);
            }
        }

        load();
        void loadBeneficiaries();
        return () => {
            cancelled = true;
        };
    }, []);

    /**
     * A name was chosen, or the choice was given up.
     *
     * The IBAN below is filled in with the grouped form, so the customer reads the account number
     * they are about to pay rather than being told a name and shown nothing. The field stays
     * editable: the grouped value is safe to send, because the server's IBAN constructor strips
     * whitespace before it validates.
     */
    function chooseBeneficiary(raw: string) {
        const chosen = beneficiaries.find((b) => String(b.id) === raw) ?? null;
        setBeneficiaryId(chosen?.id ?? null);
        setTargetIban(chosen ? formatIban(chosen.iban) : '');
    }

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

        // Basic IBAN check, skipped explicitly rather than by luck when a saved payee names the
        // destination instead. The two are never both sent: the server refuses a request that
        // names both, and a request that named both would be this form having lost track of
        // which destination the customer meant.
        if (beneficiaryId == null && !targetIban.trim()) {
            setInfo({
                type: 'error',
                messages: ['Target IBAN is required.'],
            });
            return;
        }

        const payload: NewPaymentRequest = beneficiaryId != null
            ? {
                sourceAccountId: selectedAccountId,
                beneficiaryId,
                amountCzk: amountValue,
                message: message.trim(),
            }
            : {
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
            // The chosen payee is cleared with them: left standing, the next payment
            // would go to the same person from behind an apparently blank form.
            setTargetIban('');
            setBeneficiaryId(null);
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
            // One table, asked for the words by the name of the call. It answers a failure with
            // no response at all as well, which is why there is no isApiError branch here any
            // more: that branch rendered the browser's own "Failed to fetch".
            setInfo({ type: 'error', messages: describeApiErrorLines(e, 'payment-create') });
        } finally {
            setSubmitting(false);
        }
    }

    // Render UI
    return (
        <div className="app-shell">
            <div className="card">
                <header className="card-header">
                    {brand}
                    {identity}
                </header>

                <div className="card-body layout">
                    <Nav role={role} current="new-payment" onNavigate={onNavigate} />

                    {/* Main payment form */}
                    <main className="form-panel">
                        <h2>New Payment</h2>

                        {loadingAccounts && <p>Loading accounts…</p>}
                        {accountsError && (
                            <p className="text-danger">Error: {accountsError}</p>
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
                                        {/*
                                          Grouped in fours like every other account number on
                                          either application. This was the last ungrouped
                                          identifier on any screen: one unbroken run of
                                          twenty-four characters beside a grouped balance and
                                          above a grouped destination. The field was re-measured
                                          for it, and for the arrow a select draws and an input
                                          does not.
                                        */}
                                        {accounts.map((acc) => (
                                            <option key={acc.id} value={acc.id}>
                                                {formatIban(acc.iban)}
                                            </option>
                                        ))}
                                    </select>
                                    <div className="field-side">
                                        Balance: {formatMoney(selectedAccount?.balance)}
                                    </div>
                                </div>

                                {/*
                                  The saved payees, above the field they fill in, so cause sits
                                  above effect: a name is chosen and the account number appears
                                  one row below.

                                  An option carries the name and nothing else. The IBAN is not
                                  repeated into it because it is about to be shown in full
                                  underneath, and the trusted flag that decides whether a payment
                                  is reviewed is not on this wire at all: a customer who can see
                                  which payee escapes the check has been shown how to walk past it.

                                  Absent entirely when there is nothing in the address book.
                                  Creating a payee is not part of this application, so an empty
                                  control would be furniture a customer can never fill, on the one
                                  form that moves money.
                                */}
                                {beneficiaries.length > 0 && (
                                    <div className="field-row">
                                        {/*
                                          One word, not "Saved beneficiary:". The label track is
                                          5.5rem and every other label on this form fits inside
                                          it; that one measures 115px and pushed its own control
                                          27px right of the three it stands with, so the form had
                                          four rows and two left edges. What is saved is said by
                                          the control being a closed list of the customer's own
                                          payees with None at its head.
                                        */}
                                        <label
                                            className="field-label"
                                            htmlFor="payment-beneficiary"
                                        >
                                            Beneficiary:
                                        </label>
                                        <select
                                            id="payment-beneficiary"
                                            className="field-input"
                                            value={beneficiaryId ?? ''}
                                            onChange={(e) => chooseBeneficiary(e.target.value)}
                                        >
                                            <option value="">None</option>
                                            {beneficiaries.map((b) => (
                                                <option key={b.id} value={b.id}>
                                                    {b.name}
                                                </option>
                                            ))}
                                        </select>
                                    </div>
                                )}

                                {/* Quieter than the accounts failure, which uses .text-danger and
                                    takes the form down with it, because this one takes nothing
                                    down: the payment can still be made by typing the number. */}
                                {beneficiariesFailed && (
                                    <p className="helper-text">
                                        Saved beneficiaries could not be loaded. Enter an IBAN
                                        below.
                                    </p>
                                )}

                                {/* Target IBAN */}
                                <div className="field-row">
                                    <label className="field-label" htmlFor="payment-target">
                                        To:
                                    </label>
                                    {/*
                                      Editable at all times, including while a payee is chosen.
                                      Made read-only it would leave no way back to typing, since
                                      the control that would give one does not exist. Touching it
                                      gives the choice up, silently: the customer has just named a
                                      different destination and saying so would be an argument
                                      about which of the two they meant.
                                    */}
                                    <input
                                        id="payment-target"
                                        className="field-input"
                                        type="text"
                                        placeholder="IBAN"
                                        value={targetIban}
                                        onChange={(e) => {
                                            setTargetIban(e.target.value);
                                            setBeneficiaryId(null);
                                        }}
                                    />
                                </div>

                                {/*
                                  The amount gets a row of its own. It was tucked on the end of
                                  the beneficiary row with its name set as an aside - the same
                                  class as the balance readout beside it - so the one figure that
                                  decides what this form does was labelled more quietly than the
                                  fields it stood next to, and measured a fifth of the optional
                                  message below it. Its label is a field label like the two
                                  above, and the currency is stated beside the box rather than
                                  inside it, where it is not something anyone types.
                                */}
                                <div className="field-row">
                                    <label className="field-label" htmlFor="payment-amount">
                                        Amount:
                                    </label>
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
                                    <div className="field-side">CZK</div>
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
                                    <div className="summary summary--danger" role="alert">
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
                                            <li>
                                                Transfer:{' '}
                                                {formatTransferId(info.result.transferId)}
                                            </li>
                                            {/* The word carries its treatment here too. This
                                                panel is where a customer first learns a payment
                                                is under review rather than sent, and the two
                                                read alike set in one colour. */}
                                            <li>
                                                Status:{' '}
                                                <span
                                                    className={`tone-${transferStatusTone(
                                                        info.result.status,
                                                    )}`}
                                                >
                                                    {transferStatusLabel(
                                                        info.result.status,
                                                        'customer',
                                                    )}
                                                </span>
                                            </li>

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
                                    {/*
                                      A draft is a thing you would press, so it is drawn as one:
                                      dashed rather than filled, and inert. The pointer is told
                                      what it is, and the form is spared a sentence explaining
                                      what is not there.
                                    */}
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
