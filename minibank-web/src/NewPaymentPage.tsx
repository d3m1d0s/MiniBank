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
import { PLANNED_TITLE } from '@shared/navigation';
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
 * Which half of this form the customer is standing in.
 *
 * The form had one press between a typed amount and a payment that had left the account, and the
 * press was reachable from the keyboard: Enter in the amount box submits, so the field never
 * blurred, the box was never rewritten in the convention it is read in, and the price was never
 * asked for. `1500` typed by somebody meaning fifteen hundred and `1.500` typed by somebody
 * meaning the same thing left as two different payments with nothing in between saying so.
 *
 * The review stage is that something. It holds the payload already built, so what is confirmed is
 * what was checked rather than whatever the boxes hold by the time the second press lands, and it
 * holds the amount in the convention the bank writes it in, which is the echo owner decision 10
 * puts in place of a separate refusal.
 */
type Stage =
    | { kind: 'form' }
    | { kind: 'review'; payload: NewPaymentRequest; amountCzech: string };

/**
 * The three boxes this form can refuse on its own, named so that a refusal can be put in one.
 *
 * A refusal used to be a sentence in the box at the foot of the form, 441px under the first
 * control it was about and identical in shape to an answer from the bank. Nothing was asked of
 * the bank in these three cases, and the field that has to change is the one thing the sentence
 * knows: it belongs at that field, and the box keeps what it is for, which is what the bank said.
 */
type FieldKey = 'source' | 'target' | 'amount';

/** Every field problem this form found on the last press, and never only the first of them. */
type FieldErrors = Partial<Record<FieldKey, string>>;

/** The box a refusal is drawn under, and the sentence the box points at with aria-describedby. */
const FIELD_ID: Record<FieldKey, string> = {
    source: 'payment-source',
    target: 'payment-target',
    amount: 'payment-amount',
};

function errorId(field: FieldKey): string {
    return `${FIELD_ID[field]}-error`;
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
     * What the last press found wrong, box by box.
     *
     * Every check runs and the decision is taken after them, which is the change: the three guards
     * used to return one at a time, so an empty form was refused for its amount, then for its
     * destination on the next press, and the customer was told a third of what was wrong each
     * time. A box mends its own entry as soon as it is typed into, so nothing here outlives what
     * it is about.
     */
    const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});

    /* The caret goes to the first box that has to change, which is also what reads the label and
       the sentence to anybody who is listening rather than looking. */
    const sourceBox = useRef<HTMLSelectElement | null>(null);
    const targetBox = useRef<HTMLInputElement | null>(null);
    const amountBox = useRef<HTMLInputElement | null>(null);

    /* The same object back where there was nothing to clear, so typing in a box that was never
       refused does not put this screen through a render for it. */
    function clearFieldError(field: FieldKey) {
        setFieldErrors((prev) => {
            if (prev[field] == null) return prev;
            const next = { ...prev };
            delete next[field];
            return next;
        });
    }

    const [stage, setStage] = useState<Stage>({ kind: 'form' });

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
        // A named payee is a destination, so the refusal about the box below is answered by this
        // control as much as by typing into it.
        if (chosen) clearFieldError('target');
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

    /**
     * Whether the payment is being read back rather than typed.
     *
     * Every field is frozen while it is true. Not decoration: the review holds the payload it was
     * built from, so a box edited underneath it would leave the screen showing one amount and the
     * confirming control sending another. The way to change something is the quiet control that
     * takes the review down, which is one press and clears nothing.
     */
    const reviewing = stage.kind === 'review';

    /**
     * Where the money is going, in the words the customer used to say it.
     *
     * Read off the payload rather than off the boxes, like everything else the review prints: the
     * two are the same string until somebody manages to make them differ, and this is the panel
     * whose job is that they cannot.
     *
     * A saved payee is named. Falling back to its account number would answer a question nobody
     * asked, and a payee whose name is missing is not a case the address book has: the list is
     * built from names.
     */
    const reviewDestination = stage.kind !== 'review'
        ? ''
        : stage.payload.beneficiaryId != null
            ? beneficiaries.find((b) => b.id === stage.payload.beneficiaryId)?.name
                ?? formatIban(targetIban)
            : formatIban(stage.payload.targetIban ?? '');

    /**
     * The form's one submit, sent to whichever half of the form is standing.
     *
     * Enter is why this exists rather than two handlers hung off two buttons. A form with a single
     * submit control fires it from any field, so the keyboard used to reach createPayment without
     * passing the blur that rewrites the amount. It reaches the review instead now, and it cannot
     * carry straight on through it: the boxes it would have been pressed from are frozen while the
     * review stands, so finishing the payment is a press on the control that says what it does.
     * The submit that IS on screen is the confirming one and only that one, so the key cannot ask
     * for the same review twice either.
     */
    function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        if (stage.kind === 'review') {
            void handleConfirmSend(stage);
            return;
        }
        handleReviewClick();
    }

    /**
     * Everything that can be decided without asking the bank, and then a stop.
     *
     * The three checks are the form's own and they say what they said before. What changed is that
     * all three are run and the decision is taken after them, and that each answer is put at the
     * box it is about rather than in one sentence at the foot of the form. What follows them is
     * unchanged: the payload is built and put on screen to be read rather than posted, and the two
     * things the blur used to do are done here, because the path that gets here need never have
     * blurred anything.
     */
    function handleReviewClick() {
        setInfo({ type: 'none' });

        const found: FieldErrors = {};

        // The source account.
        if (selectedAccountId == null) {
            found.source = 'Choose the account this payment leaves.';
        }

        // The amount. The parser's reason is shown as given: it names what is wrong with this
        // string, which one generic "invalid amount" cannot, and the amounts people get wrong are
        // the ones where the difference between two readings is a factor of a thousand.
        const parsed = parseAmount(amount, readerLocale());
        if (!parsed.ok) {
            found.amount = parsed.reason;
        }

        // The destination, skipped explicitly rather than by luck when a saved payee names it
        // instead. The two are never both sent: the server refuses a request that names both, and
        // a request that named both would be this form having lost track of which destination the
        // customer meant.
        if (beneficiaryId == null && !targetIban.trim()) {
            found.target = beneficiaries.length > 0
                ? 'Enter the account number this payment goes to, or choose a saved beneficiary.'
                : 'Enter the account number this payment goes to.';
        }

        setFieldErrors(found);
        // The same three conditions again rather than a look at what was collected, and this is
        // the reason: written against `found` the compiler learns nothing from the return, and
        // the account id and the parsed amount below would both have to be asserted back into
        // existence. Read this way, what the checks proved is what the rest of the function has.
        if (selectedAccountId == null || !parsed.ok || found.target != null) {
            // In the order the boxes stand in, so the caret lands in the first one a reader
            // coming down the form would have reached.
            const first = found.source
                ? sourceBox
                : found.target
                    ? targetBox
                    : amountBox;
            first.current?.focus();
            return;
        }

        const amountValue = parsed.value;

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

        // The box is rewritten in the bank's convention here and not only on blur: on the Enter
        // path nothing ever blurred, so `1.500` stayed on screen as `1.500` while the review
        // beneath it said what the bank had read. The two must not be able to disagree.
        setAmount(parsed.czech);

        // And the price is asked for the same three values. Every keystroke in the amount drops
        // the quote, so on the Enter path there is none: without this the review would offer to
        // send a payment with no fee and no total under it, which is the number the whole block
        // was added for.
        void askForQuote(selectedAccountId, beneficiaryId, amount);

        setStage({ kind: 'review', payload, amountCzech: parsed.czech });
    }

    /**
     * The second press, and the only one that moves money.
     *
     * It takes the payload the review was drawn from rather than rebuilding it out of the boxes:
     * the whole point of the stop is that what is confirmed is what was read.
     */
    async function handleConfirmSend(reviewed: { payload: NewPaymentRequest }) {
        const payload = reviewed.payload;

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
            // On both outcomes, and the refused one is the reason it is here rather than beside
            // the receipt. Left standing after a refusal the review would offer the confirming
            // control again over a payment that may already have been carried out; the form comes
            // back instead, with what was typed still in it and the refusal above it.
            setStage({ kind: 'form' });
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
                            <form className="form" onSubmit={handleSubmit}>
                                {/* From account */}
                                <div className="field-row">
                                    <label className="field-label" htmlFor="payment-source">
                                        From:
                                    </label>
                                    <select
                                        id="payment-source"
                                        className="field-input"
                                        ref={sourceBox}
                                        value={selectedAccountId ?? ''}
                                        disabled={reviewing}
                                        aria-invalid={fieldErrors.source ? true : undefined}
                                        aria-describedby={
                                            fieldErrors.source ? errorId('source') : undefined
                                        }
                                        onChange={(e) => {
                                            const chosen = Number(e.target.value);
                                            setSelectedAccountId(chosen);
                                            clearFieldError('source');
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
                                    {/* Under the box it is about, on a line of its own: the row
                                        is a wrapping flex line and the sentence claims the whole
                                        of the next one. aria-describedby above is what ties the
                                        two together for a reader who is not looking at either. */}
                                    {fieldErrors.source && (
                                        <p className="field-error" id={errorId('source')}>
                                            {fieldErrors.source}
                                        </p>
                                    )}
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
                                            disabled={reviewing}
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
                                        ref={targetBox}
                                        value={targetIban}
                                        disabled={reviewing}
                                        aria-invalid={fieldErrors.target ? true : undefined}
                                        aria-describedby={
                                            fieldErrors.target ? errorId('target') : undefined
                                        }
                                        onChange={(e) => {
                                            setTargetIban(e.target.value);
                                            setBeneficiaryId(null);
                                            clearFieldError('target');
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
                                    {fieldErrors.target && (
                                        <p className="field-error" id={errorId('target')}>
                                            {fieldErrors.target}
                                        </p>
                                    )}
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
                                        ref={amountBox}
                                        value={amount}
                                        disabled={reviewing}
                                        aria-invalid={fieldErrors.amount ? true : undefined}
                                        aria-describedby={
                                            fieldErrors.amount ? errorId('amount') : undefined
                                        }
                                        onChange={(e) => {
                                            setAmount(e.target.value);
                                            clearFieldError('amount');
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
                                    {fieldErrors.amount && (
                                        <p className="field-error" id={errorId('amount')}>
                                            {fieldErrors.amount}
                                        </p>
                                    )}
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
                                {/* Silent while the review stands, which is the only time these
                                    three figures are on screen twice. The panel below prints the
                                    same amount, fee and total against the payload it will send,
                                    and two readings of one price 300px apart invite the reader to
                                    look for the difference between them. */}
                                {quote && !reviewing && (
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
                                        disabled={reviewing}
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
                                    /* The payment exists, in all three of the branches below: the
                                       bank took it, and what is left to happen to it is what the
                                       words inside say. That is the one thing this edge claims,
                                       and it is the edge every other answered press on these
                                       screens takes when the answer is yes. */
                                    <div className="summary summary--success" role="status">
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

                                {/*
                                  The stop between a typed payment and a sent one, and it is a
                                  panel on the form rather than a dialog over it, per owner
                                  decision 14. A modal would have to be dismissed to re-read the
                                  boxes it is about; this one stands under them with every field
                                  frozen, so what is being confirmed and what was typed are on
                                  screen together.

                                  It is also the echo. The amount here is written the way the bank
                                  writes it, so `1.500` typed by somebody who meant fifteen hundred
                                  is read back as `1 500,00` before it is anybody's money, which is
                                  the reading decision 10 gives instead of a separate refusal.
                                */}
                                {stage.kind === 'review' ? (
                                    <div className="summary summary--neutral gap-above-md">
                                        <div className="summary-title">
                                            Check this payment before it is sent
                                        </div>
                                        <ul>
                                            {/* The payee's own name where one was chosen, because
                                                that is what the customer picked and the account
                                                number under it is the bank's spelling of it. A
                                                typed destination has no name to give and is
                                                grouped, like every other account number here. */}
                                            <li>To: {reviewDestination}</li>

                                            {/*
                                              The price as the bank quoted it, and the amount cell
                                              of it is the echo: formatMoney is pinned to the Czech
                                              convention, so this line is where the customer reads
                                              their own figure back. The fee and the total ride
                                              with it because the total is what leaves the account
                                              and the fee is the number nobody sees coming.

                                              Where the quote could not be got the amount is still
                                              printed, alone. A review that goes silent because a
                                              price is missing would be a stop that stops nothing:
                                              the sentence about the failed quote already stands
                                              above, beside the field it is about.
                                            */}
                                            {quote ? (
                                                QUOTE_FIELDS.map((f) => (
                                                    <li key={f}>
                                                        {QUOTE_LABEL[f]}:{' '}
                                                        {formatMoney(quote[f])}
                                                    </li>
                                                ))
                                            ) : (
                                                <li>
                                                    {QUOTE_LABEL.amount}:{' '}
                                                    {stage.amountCzech} CZK
                                                </li>
                                            )}

                                            {/* Only where something was written. An empty line
                                                labelled Message would say a message was sent. */}
                                            {stage.payload.message && (
                                                <li>Message: {stage.payload.message}</li>
                                            )}
                                        </ul>

                                        {quote && authorizationNote(quote.authorizationRequired) && (
                                            <p className="helper-text">
                                                {authorizationNote(quote.authorizationRequired)}
                                            </p>
                                        )}

                                        {/*
                                          The only submit on screen while this stands, which is
                                          what makes Enter finish the payment instead of asking
                                          for the review a second time. Its label is the one the
                                          form's own button used to carry, because this is now the
                                          press that sends.
                                        */}
                                        <div className="summary-actions">
                                            <button
                                                type="submit"
                                                className="btn-primary"
                                                disabled={submitting}
                                            >
                                                {submitting ? 'Sending…' : 'Send now'}
                                            </button>
                                            {/* Quiet, and it decides nothing: it puts the form
                                                back the way it was, with every box still holding
                                                what was typed into it. */}
                                            <button
                                                type="button"
                                                className="btn-quiet"
                                                onClick={() => setStage({ kind: 'form' })}
                                                disabled={submitting}
                                            >
                                                Change this payment
                                            </button>
                                        </div>
                                    </div>
                                ) : (
                                    <div className="actions">
                                        {/* It no longer sends, so it no longer says it does. The
                                            word that names what this press does is on the button
                                            in the panel above, where the money actually moves. */}
                                        <button type="submit" className="btn-primary">
                                            Review payment
                                        </button>
                                        {/*
                                          A draft is a thing you would press, so it is drawn as
                                          one: dashed rather than filled, and inert. The pointer is
                                          told what it is, and the form is spared a sentence
                                          explaining what is not there.

                                          A span, not a disabled button, for the reason the
                                          navigation column gives at Nav.tsx:41: a button with no
                                          handler was the eleventh and last stop of the form's
                                          keyboard walk, so somebody working the form by keys
                                          ended on the one control that answers nothing. Drawn
                                          with the same two classes, so the dashed pill is
                                          unchanged; what goes is the tab stop and the
                                          aria-disabled that promised the press would come back.
                                        */}
                                        <span
                                            className="btn-secondary planned-item"
                                            title={PLANNED_TITLE}
                                        >
                                            Save as draft
                                        </span>
                                    </div>
                                )}
                            </form>
                        )}
                    </main>
                </div>
            </div>
        </div>
    );
}
