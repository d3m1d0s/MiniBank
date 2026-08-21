// src/NewPaymentPage.tsx

import { useEffect, useRef, useState, type ReactNode } from 'react';
import './App.css';
import {
    getMyAccounts,
    createPayment,
    fetchMyBeneficiaries,
    fetchPaymentQuote,
    type AccountSummary,
    type Beneficiary,
    type NewPaymentRequest,
    type NewPaymentResult,
    type PaymentQuote,
    isUnderReview,
} from './api';
import { formatMoney, readerLocale } from './money';
import { parseAmount } from '@shared/money';
import ErrorBox from './ErrorBox';
import { describeApiFailure, type ApiFailure } from '@shared/apiErrors';
import { authorizationNote, transferStatusLabel, transferStatusTone } from '@shared/glossary';
import { ACCOUNT_LABEL, QUOTE_FIELDS, QUOTE_LABEL } from '@shared/fields';
import { formatIban, formatTransferId } from '@shared/format';
import Nav from './Nav';
import type { NavRole, NavView } from '@shared/navigation';

const MAX_MESSAGE_LENGTH = 140;

/**
 * What the panel under the form is saying, and it says one thing at a time.
 *
 * The failure is carried in the shared shape rather than as loose strings so that the box under
 * this form is the box every other screen draws: sentences, and where there is one, the reference
 * for the small type. A refusal the form worked out for itself has no reference and says so with
 * null, which is exactly what an answer that never left the browser should print.
 */
type InfoState =
    | { type: 'none' }
    | { type: 'success'; result: NewPaymentResult }
    | { type: 'error'; failure: ApiFailure };

/**
 * A refusal this form worked out for itself, in the shape the box renders.
 *
 * No reference, because nothing was asked of the bank: printing "HTTP 0" under a sentence about an
 * empty IBAN box would name an answer that does not exist. No retry either, for the same reason -
 * the way out of these three is the field they name.
 */
function refusedHere(line: string): ApiFailure {
    return { lines: [line], reference: null };
}

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
    const [accountsError, setAccountsError] = useState<ApiFailure | null>(null);

    /* Whether this screen is still on. The accounts are read from the mount effect and again from
       the retry beside their own error box, so the guard cannot live in a local of the effect. */
    const alive = useRef(true);

    const [submitting, setSubmitting] = useState(false);
    const [info, setInfo] = useState<InfoState>({ type: 'none' });

    /**
     * What the bank says this payment would cost, and why it might refuse to price it.
     *
     * Quoted rather than computed here. The tariff has a step in it, free below a threshold and
     * charged above, so a customer met the fee for the first time on the receipt; a copy of the
     * step in this file would be a second tariff that goes on quoting last month's price. The
     * refusal is worth showing for the same reason: an amount past the account's daily ceiling is
     * refused by the quote in the same words the submit would use, before the money moves.
     */
    const [quote, setQuote] = useState<PaymentQuote | null>(null);
    const [quoteError, setQuoteError] = useState<ApiFailure | null>(null);

    /**
     * Which quote request is the current one.
     *
     * Every answer carries the amount it was asked about, and the reader cannot see which: a
     * price for 1 500,00 landing under a box that now reads 15 000,00 is a wrong number rather
     * than a stale one. The counter is bumped on every ask AND on every keystroke in the amount,
     * so an answer already in flight cannot come back and stand under an amount nobody quoted.
     */
    const quoteToken = useRef(0);

    // Load accounts when the component is mounted
    useEffect(() => {
        alive.current = true;
        void loadAccounts();
        void loadBeneficiaries();
        return () => {
            alive.current = false;
        };
    }, []);

    /**
     * The accounts, without which there is no form at all.
     *
     * Its failure is the one on this screen that leaves nothing to do, and there was no way out of
     * it: the screen is mounted by a nav click and clicking the entry the customer is standing on
     * does not mount it again, so the only recovery was the browser's reload button. It is a read
     * and costs nothing to ask twice, which is exactly the case the retry is for.
     */
    async function loadAccounts() {
        try {
            setLoadingAccounts(true);
            setAccountsError(null);
            const data = await getMyAccounts();
            if (!alive.current) return;
            setAccounts(data);
            if (data.length > 0) {
                setSelectedAccountId(data[0].id);
            }
        } catch (e) {
            if (!alive.current) return;
            setAccountsError(
                describeApiFailure(e, 'accounts', { retry: () => void loadAccounts() }),
            );
        } finally {
            if (alive.current) setLoadingAccounts(false);
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
            if (!alive.current) return;
            setBeneficiaries(saved);
            setBeneficiariesFailed(false);
        } catch {
            if (!alive.current) return;
            setBeneficiaries([]);
            setBeneficiariesFailed(true);
        }
    }

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
        // The destination decides whether a code is asked for, so the quote is re-asked rather
        // than left standing: the same amount settles at once to a payee the bank trusts.
        void askForQuote(selectedAccountId, chosen?.id ?? null, amount);
    }

    /**
     * Drops whatever price is on screen, because the payment it was about has changed.
     *
     * The token goes up with it: without that, an answer already in flight for the old amount
     * would arrive after this and put the old price back under the new one.
     */
    function forgetQuote() {
        quoteToken.current += 1;
        setQuote(null);
        setQuoteError(null);
    }

    /**
     * Asks the bank what this payment would cost, if there is enough of a payment to price.
     *
     * The three inputs are passed rather than read off the state, because every call site is a
     * change handler: the state it just set is not visible to it, and quoting the previous
     * account is worse than not quoting at all.
     */
    async function askForQuote(
        accountId: number | null,
        payeeId: number | null,
        rawAmount: string,
    ) {
        const parsed = parseAmount(rawAmount, readerLocale());
        if (accountId == null || !parsed.ok) {
            forgetQuote();
            return;
        }

        const token = ++quoteToken.current;

        try {
            const priced = await fetchPaymentQuote(accountId, parsed.value, payeeId);
            if (token !== quoteToken.current) return;
            setQuote(priced);
            setQuoteError(null);
        } catch (e) {
            if (token !== quoteToken.current) return;
            // The price is gone as well as unknown: a figure left standing beside the sentence
            // explaining why it could not be got would be read as the answer.
            setQuote(null);
            // A price is a read and asking again is free, so this one carries a retry, and it
            // re-asks with the values that were quoted rather than with whatever is in the boxes
            // by the time it is pressed.
            setQuoteError(
                describeApiFailure(e, 'payment-quote', {
                    retry: () => void askForQuote(accountId, payeeId, rawAmount),
                }),
            );
        }
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
            setInfo({ type: 'error', failure: refusedHere('Please select source account.') });
            return;
        }

        // Parse amount. The reason is shown as given: it names what is wrong with this string,
        // which one generic "invalid amount" cannot, and the amounts people get wrong are the
        // ones where the difference between two readings is a factor of a thousand.
        const parsed = parseAmount(amount, readerLocale());
        if (!parsed.ok) {
            setInfo({ type: 'error', failure: refusedHere(parsed.reason) });
            return;
        }
        const amountValue = parsed.value;

        // Basic IBAN check, skipped explicitly rather than by luck when a saved payee names the
        // destination instead. The two are never both sent: the server refuses a request that
        // names both, and a request that named both would be this form having lost track of
        // which destination the customer meant.
        if (beneficiaryId == null && !targetIban.trim()) {
            setInfo({ type: 'error', failure: refusedHere('Target IBAN is required.') });
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
            // The quote priced the payment that has just been sent. Left on screen under an
            // empty amount box it would read as a price for the next one.
            forgetQuote();

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
            //
            // No retry, and this is the call the rule was written for: a second press is a second
            // payment, and a request that timed out may have been carried out.
            setInfo({ type: 'error', failure: describeApiFailure(e, 'payment-create') });
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

                        {/* One statement at the head of the form: what is on its way, then why
                            nothing came and the way to ask again, then the empty case. They were
                            three independent conditions, so a failed read printed its sentence
                            with no way out of it. */}
                        {loadingAccounts ? (
                            <p className="helper-text">Loading accounts…</p>
                        ) : accountsError ? (
                            /* The plain title. One branch of the wording table for this call
                               already says "Your accounts could not be loaded.", and a heading
                               saying it again above it read as a stutter. */
                            <ErrorBox failure={accountsError} />
                        ) : accounts.length === 0 ? (
                            <p className="helper-text">No accounts available.</p>
                        ) : null}

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
                                        onChange={(e) => {
                                            const chosen = Number(e.target.value);
                                            setSelectedAccountId(chosen);
                                            // Each account has its own ceiling and its own day
                                            // total, so the price and the answer about a code
                                            // belong to the account, not to the amount alone.
                                            void askForQuote(chosen, beneficiaryId, amount);
                                        }}
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
                                        {ACCOUNT_LABEL.balance}:{' '}
                                        {formatMoney(selectedAccount?.balance)}
                                    </div>
                                </div>

                                {/*
                                  The three numbers that decide what may leave this account, under
                                  the account they belong to.

                                  They were invisible, and the behaviour they produce therefore
                                  looked arbitrary: the same amount settled at once in the morning
                                  and asked for a one time code in the afternoon, with nothing on
                                  this form saying that the difference was the day's running total.
                                  Spent today is what the other two are measured against, so it is
                                  printed beside them rather than left to be worked out.

                                  The threshold line is absent when the account has none of its
                                  own. The bank-wide default is not printed in its place: stated
                                  on this row it would look like a property of this account, and
                                  it moves when the bank moves it.
                                */}
                                {selectedAccount && (
                                    <div className="fact-line under-field">
                                        <span>
                                            <span className="fact-label">
                                                {ACCOUNT_LABEL.spentToday}:
                                            </span>{' '}
                                            <span className="fact-value">
                                                {formatMoney(selectedAccount.spentToday)}
                                            </span>
                                        </span>
                                        <span>
                                            <span className="fact-label">
                                                {ACCOUNT_LABEL.dailyLimit}:
                                            </span>{' '}
                                            <span className="fact-value">
                                                {formatMoney(selectedAccount.dailyLimit)}
                                            </span>
                                        </span>
                                        {selectedAccount.softDailyThreshold && (
                                            <span>
                                                <span className="fact-label">
                                                    {ACCOUNT_LABEL.softDailyThreshold}:
                                                </span>{' '}
                                                <span className="fact-value">
                                                    {formatMoney(
                                                        selectedAccount.softDailyThreshold,
                                                    )}
                                                </span>
                                            </span>
                                        )}
                                    </div>
                                )}

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
                                            // Only where a choice is actually being given up.
                                            // A typed destination has no payee behind it and is
                                            // never trusted, so the answer about a code can
                                            // change; the price cannot, and neither can either
                                            // of them on the next keystroke.
                                            if (beneficiaryId != null) {
                                                void askForQuote(selectedAccountId, null, amount);
                                            }
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
                                        onChange={(e) => {
                                            setAmount(e.target.value);
                                            // Half an amount is not an amount, and a price for
                                            // the previous one standing under it is a wrong
                                            // number rather than an old one.
                                            forgetQuote();
                                        }}
                                        onBlur={() => {
                                            const parsed = parseAmount(amount, readerLocale());
                                            if (parsed.ok) {
                                                setAmount(parsed.czech);
                                            }
                                            // Priced on blur for the reason the box is
                                            // normalized on blur: while someone is still typing
                                            // there is nothing settled to price.
                                            void askForQuote(
                                                selectedAccountId,
                                                beneficiaryId,
                                                amount,
                                            );
                                        }}
                                    />
                                    <div className="field-side">CZK</div>
                                </div>

                                {/*
                                  What the bank says this payment costs, before it is sent.

                                  Three figures rather than one, because the fee is the number
                                  nobody could see coming: the tariff is free below a threshold
                                  and charged above it, so a heller past a boundary is ten crowns,
                                  and the total is what actually leaves the account. The sentence
                                  under them is spoken only when there will be a code, by the same
                                  restraint the rest of these screens use: a line that appears on
                                  every payment has stopped being read by the time it matters.
                                */}
                                {quote && (
                                    <div className="under-field">
                                        <div className="fact-line">
                                            {QUOTE_FIELDS.map((f) => (
                                                <span key={f}>
                                                    <span className="fact-label">
                                                        {QUOTE_LABEL[f]}:
                                                    </span>{' '}
                                                    <span className="fact-value">
                                                        {formatMoney(quote[f])}
                                                    </span>
                                                </span>
                                            ))}
                                        </div>
                                        {authorizationNote(quote.authorizationRequired) && (
                                            <p className="helper-text">
                                                {authorizationNote(quote.authorizationRequired)}
                                            </p>
                                        )}
                                    </div>
                                )}

                                {/*
                                  The quote refusing is worth saying. It prices a payment and does
                                  not accept one, so nothing here is about the balance; what it
                                  does refuse is an amount past the account's daily ceiling, in
                                  the same words the submit would use and before the money moves.
                                */}
                                {/*
                                  Kept as lines under the field rather than promoted to the box
                                  the rest of this application draws a failure in: nothing has
                                  been sent, nothing is lost, and a bordered panel would be
                                  louder than the price it stands in for. The reference and the
                                  way to ask again come with it all the same, in the same quiet
                                  type as the sentences.
                                */}
                                {quoteError && (
                                    <div className="under-field">
                                        {quoteError.lines.map((line) => (
                                            <p key={line} className="helper-text text-danger">
                                                {line}
                                            </p>
                                        ))}
                                        {quoteError.retry && (
                                            <button
                                                type="button"
                                                className="btn-quiet"
                                                onClick={quoteError.retry}
                                            >
                                                {quoteError.retryLabel}
                                            </button>
                                        )}
                                        {quoteError.reference && (
                                            <p className="summary-reference">
                                                {quoteError.reference}
                                            </p>
                                        )}
                                    </div>
                                )}

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
                                    <ErrorBox
                                        failure={info.failure}
                                        title="We could not send this payment"
                                    />
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
