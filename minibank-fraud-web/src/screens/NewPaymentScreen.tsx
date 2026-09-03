import { useCallback, useEffect, useRef, useState } from 'react';
import {
    createPayment,
    fetchMyBeneficiaries,
    fetchPaymentQuote,
    formatMoney,
    getMyAccounts,
    isUnderReview,
    parseAmount,
    type AccountSummary,
    type Beneficiary,
    type DailyOutflow,
    type NewPaymentRequest,
    type NewPaymentResult,
    type PaymentQuote,
} from '../lib/api';
import { readerLocale } from '../lib/locale';
import {
    checkPaymentForm,
    type PaymentField,
    type PaymentProblems,
} from '@shared/logic/paymentForm';
import ErrorBox from '../ui/ErrorBox';
import { describeApiFailure, type ApiFailure } from '@shared/wire/apiErrors';
import { authorizationNote, transferStatusLabel, transferStatusTone } from '@shared/text/glossary';
import { ACCOUNT_LABEL, DAILY_OUTFLOW_LABEL, QUOTE_FIELDS, QUOTE_LABEL } from '@shared/text/fields';
import { formatIban, formatTransferId } from '@shared/text/format';
import { PLANNED_TITLE } from '@shared/nav/navigation';

const MAX_MESSAGE_LENGTH = 140;

/**
 * What the panel under the form is saying, and it says one thing at a time.
 *
 * The failure is carried in the shared shape rather than as loose strings, so the box under this
 * form is the box every other screen of this window draws: sentences, and where there is one, the
 * reference for the small type.
 */
type InfoState =
    | { type: 'none' }
    | { type: 'success'; result: NewPaymentResult }
    | { type: 'error'; failure: ApiFailure };

/**
 * Which half of this form the customer is standing in.
 *
 * The review stage holds the payload already built, so what is confirmed is what was checked rather
 * than whatever the boxes hold by the time the second press lands, and it holds the amount in the
 * convention the bank writes it in, which is the echo owner decision 10 puts in place of a separate
 * refusal.
 */
type Stage =
    | { kind: 'form' }
    | { kind: 'review'; payload: NewPaymentRequest; amountCzech: string };

/*
 * The three boxes this form can refuse on its own are named in @shared/paymentForm, together with
 * the checks and the three sentences, because the customer application's form asks the same three
 * questions and asked them in its own copy of the same block.
 *
 * What stays here is where a refusal is drawn, which is this window's business and not the other
 * one's: the two skins put a sentence under a control by different means.
 */

/** The box a refusal is drawn under, and the sentence the box points at with aria-describedby. */
const FIELD_ID: Record<PaymentField, string> = {
    source: 'payment-source',
    target: 'payment-target',
    amount: 'payment-amount',
};

function errorId(field: PaymentField): string {
    return `${FIELD_ID[field]}-error`;
}

/**
 * THE ONE FORM IN THIS WINDOW THAT MOVES MONEY.
 *
 * Read once from top to bottom, with exactly one irreversible press and a compulsory stop in front
 * of it. Weight goes, in order, to the amount box, the two figures of the customer's day, and the
 * primary button; it is withheld from the message box, which is the widest and quietest thing here,
 * from the dashed draft pill, and from the account selector.
 *
 * The logic is the customer application's, call for call, because owner decision 1 says a customer
 * is offered the same fields and the same actions on both platforms. What differs is the markup and
 * the skin. Everything that decides a word, a format or an error sentence is read from the shared
 * layer, so the two forms cannot come to say different things about one payment.
 */
export default function NewPaymentScreen({ parked }: { parked: boolean }) {
    const [accounts, setAccounts] = useState<AccountSummary[]>([]);
    const [selectedAccountId, setSelectedAccountId] = useState<number | null>(null);

    /**
     * The customer's day, which is one day and not one per account.
     *
     * Held beside the accounts and never inside them. It arrives on the same answer, so it is as
     * fresh as the balances drawn from that answer and goes stale with them: one payment moves
     * both.
     */
    const [today, setToday] = useState<DailyOutflow | null>(null);

    const [targetIban, setTargetIban] = useState('');
    const [amount, setAmount] = useState('');
    const [message, setMessage] = useState('');

    /**
     * The customer's saved payees, and which one is chosen.
     *
     * Two ways to name a destination and both stay: choosing a name sends its id, which is what
     * reaches the trusted branch of the risk rules, and typing an account number sends the number,
     * which has no payee behind it and never can. Last touched wins.
     *
     * The list failing is not the form failing, so it has a flag of its own rather than sharing the
     * accounts error, which kills the form: a payment can still be made by typing an IBAN.
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
     * Every check runs and the decision is taken after them: guards that return one at a time tell
     * the customer a third of what is wrong per press. A box mends its own entry as soon as it is
     * typed into, so nothing here outlives what it is about.
     */
    const [fieldErrors, setFieldErrors] = useState<PaymentProblems>({});

    /* The caret goes to the first box that has to change, which is also what reads the label and
       the sentence to anybody who is listening rather than looking. */
    const sourceBox = useRef<HTMLSelectElement | null>(null);
    const targetBox = useRef<HTMLInputElement | null>(null);
    const amountBox = useRef<HTMLInputElement | null>(null);

    /* The same object back where there was nothing to clear, so typing in a box that was never
       refused does not put this screen through a render for it. */
    function clearFieldError(field: PaymentField) {
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
     * charged above, so a copy of the step in this file would be a second tariff quoting last
     * month's price. The refusal is worth showing for the same reason: an amount past the
     * customer's daily ceiling is refused by the quote in the same words the submit would use,
     * before the money moves.
     */
    const [quote, setQuote] = useState<PaymentQuote | null>(null);
    const [quoteError, setQuoteError] = useState<ApiFailure | null>(null);

    /**
     * Which quote request is the current one.
     *
     * Every answer carries the amount it was asked about and the reader cannot see which: a price
     * for 1 500,00 landing under a box that now reads 15 000,00 is a wrong number rather than a
     * stale one. The counter is bumped on every ask and on every keystroke in the amount.
     */
    const quoteToken = useRef(0);


    /**
     * The accounts, without which there is no form at all.
     *
     * Its failure is the one on this screen that leaves nothing to do, and it is a read that costs
     * nothing to ask twice, which is exactly the case a retry is for.
     */
    /*
     * useCallback with an empty list, and the list is empty because it is true rather than to
     * silence anything: everything this closes over is stable across renders - the state setters,
     * the ref and the fetch - so the function never has to be rebuilt, and the effect below can
     * name it and still run once.
     */
    const loadAccounts = useCallback(async () => {
        try {
            setLoadingAccounts(true);
            setAccountsError(null);
            const data = await getMyAccounts();
            if (!alive.current) return;
            setAccounts(data.accounts);
            setToday(data.today);
            if (data.accounts.length > 0) {
                setSelectedAccountId(data.accounts[0].id);
            }
        } catch (e) {
            if (!alive.current) return;
            setAccountsError(
                describeApiFailure(e, 'accounts', { retry: () => void loadAccounts() }),
            );
        } finally {
            if (alive.current) setLoadingAccounts(false);
        }
    }, []);

    /**
     * The address book, fetched beside the accounts and gating nothing.
     *
     * A slow list of payees must not delay a payment, so this has no loading line of its own: the
     * row simply is not there until the names arrive.
     */
    const loadBeneficiaries = useCallback(async () => {
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
    }, []);

    /* Declared after both loaders and not before them: a name in a dependency array is read while
       the component renders, and a const read before its own line is a ReferenceError. */
    useEffect(() => {
        alive.current = true;
        void loadAccounts();
        void loadBeneficiaries();
        return () => {
            alive.current = false;
        };
    }, [loadAccounts, loadBeneficiaries]);

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
        // The destination decides whether a code is asked for, so the quote is re-asked rather than
        // left standing: the same amount settles at once to a payee the bank trusts.
        void askForQuote(selectedAccountId, chosen?.id ?? null, amount);
    }

    /**
     * Drops whatever price is on screen, because the payment it was about has changed. The token
     * goes up with it, or an answer already in flight would put the old price under the new amount.
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
     * change handler: the state it has just set is not visible to it, and quoting the previous
     * account is worse than not quoting at all.
     */
    async function askForQuote(accountId: number | null, payeeId: number | null, rawAmount: string) {
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
            setQuoteError(
                describeApiFailure(e, 'payment-quote', {
                    retry: () => void askForQuote(accountId, payeeId, rawAmount),
                }),
            );
        }
    }

    const selectedAccount =
        selectedAccountId != null
            ? accounts.find((a) => a.id === selectedAccountId) ?? null
            : null;

    /**
     * Whether the payment is being read back rather than typed.
     *
     * Every field is frozen while it is true, and that is not decoration: the review holds the
     * payload it was built from, so a box edited underneath it would leave the screen showing one
     * amount and the confirming control sending another.
     */
    const reviewing = stage.kind === 'review';

    /**
     * Where the money is going, in the words the customer used to say it. Read off the payload
     * rather than off the boxes, like everything else the review prints.
     */
    const reviewDestination =
        stage.kind !== 'review'
            ? ''
            : stage.payload.beneficiaryId != null
                ? beneficiaries.find((b) => b.id === stage.payload.beneficiaryId)?.name
                    ?? formatIban(targetIban)
                : formatIban(stage.payload.targetIban ?? '');

    /**
     * The form's one submit, sent to whichever half of the form is standing.
     *
     * Enter is why this exists rather than two handlers hung off two buttons. A form with a single
     * submit control fires it from any field, so the keyboard would otherwise reach createPayment
     * without passing the blur that rewrites the amount. It reaches the review instead, and it
     * cannot carry straight on through it: the boxes it would have been pressed from are frozen
     * while the review stands.
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
     * The three checks are not written here any more. They are checkPaymentForm, because the
     * customer application's form carried the same block character for character, the three
     * sentences included, and two copies of one rule agree only on the day they are written. What
     * this function keeps is everything that is about this screen: which box the caret goes to,
     * what is drawn under each one, and what happens once nothing is wrong.
     *
     * All three checks still run and the decision is still taken after them, and each answer is
     * still put at the box it is about rather than in one sentence at the foot of the form.
     */
    function handleReviewClick() {
        setInfo({ type: 'none' });

        const check = checkPaymentForm(
            {
                selectedAccountId,
                amount,
                beneficiaryId,
                targetIban,
                hasSavedBeneficiaries: beneficiaries.length > 0,
            },
            readerLocale(),
        );

        setFieldErrors(check.problems);
        if (check.first != null) {
            // The box the check named, which is the first one a reader coming down the form would
            // have reached and not the first check that ran.
            const box = check.first === 'source'
                ? sourceBox
                : check.first === 'target'
                    ? targetBox
                    : amountBox;
            box.current?.focus();
            return;
        }

        // The two conditions again rather than a look at what came back, and this is the reason:
        // `check.first` being null proves nothing to the compiler, and the account id and the
        // parsed amount below would both have to be asserted back into existence. What the
        // customer is answered with is `check.first`; this pair is what the types need, and the
        // two cannot disagree because both are read off the one check.
        const parsed = check.amount;
        if (selectedAccountId == null || !parsed.ok) return;

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
        // path nothing ever blurred, so `1.500` would stay on screen while the review beneath it
        // said what the bank had read. The two must not be able to disagree.
        setAmount(parsed.czech);

        // And the price is asked for the same three values. Every keystroke in the amount drops the
        // quote, so on the Enter path there is none, and the review would otherwise offer to send a
        // payment with no fee and no total under it.
        void askForQuote(selectedAccountId, beneficiaryId, amount);

        setStage({ kind: 'review', payload, amountCzech: parsed.czech });
    }

    /**
     * The second press, and the only one that moves money. It takes the payload the review was
     * drawn from rather than rebuilding it out of the boxes: the whole point of the stop is that
     * what is confirmed is what was read.
     */
    async function handleConfirmSend(reviewed: { payload: NewPaymentRequest }) {
        const payload = reviewed.payload;

        try {
            setSubmitting(true);
            const result = await createPayment(payload);
            setInfo({ type: 'success', result });

            // The fields have done their job; a form that keeps them re-sends the same payment on
            // one stray Enter. The chosen payee is cleared with them, or the next payment would go
            // to the same person from behind an apparently blank form.
            setTargetIban('');
            setBeneficiaryId(null);
            setAmount('');
            setMessage('');
            // The quote priced the payment that has just been sent. Left under an empty amount box
            // it would read as a price for the next one.
            forgetQuote();

            // The payment may have moved money, so the balances fetched on mount are stale next to
            // the confirmation's new one, and the day's total is stale by the same amount.
            try {
                const refreshed = await getMyAccounts();
                setAccounts(refreshed.accounts);
                setToday(refreshed.today);
            } catch {
                // The payment itself succeeded, and the confirmation carries the authoritative
                // balance whatever this refresh does.
            }
        } catch (e) {
            // No retry, and this is the call the rule was written for: a second press is a second
            // payment, and a request that timed out may have been carried out.
            setInfo({ type: 'error', failure: describeApiFailure(e, 'payment-create') });
        } finally {
            // On both outcomes, and the refused one is why it is here rather than beside the
            // receipt: left standing after a refusal the review would offer the confirming control
            // again over a payment that may already have been carried out.
            setStage({ kind: 'form' });
            setSubmitting(false);
        }
    }

    return (
        // panel first, and it is the same class the desk's two regions wear: a customer screen is a
        // sheet standing on the window's ground, not ink printed on it. Without it these screens
        // were the dark application's page in a light palette, which is the one thing this skin is
        // not.
        <main className={parked ? 'panel form-panel screen-parked' : 'panel form-panel'}>
            <section className="section">
                {/* The screen names itself where the work begins, at the rung this window sets
                    every block name at: told apart by weight and never by size, because the
                    largest thing on this screen is the amount. */}
                <h2 className="section-title">New payment</h2>

                {/* One statement at the head of the form: what is on its way, then why nothing
                    came and the way to ask again, then the empty case. */}
                <div className="section-block">
                    {loadingAccounts ? (
                        <p className="helper-text">Loading accounts…</p>
                    ) : accountsError ? (
                        /* No title. One branch of the wording table for this call already says
                           the accounts could not be loaded, and a heading saying it again above
                           it reads as a stutter. */
                        <ErrorBox failure={accountsError} />
                    ) : accounts.length === 0 ? (
                        <p className="helper-text">No accounts available.</p>
                    ) : null}
                </div>

                {/*
                  THE CUSTOMER'S DAY, above the form and outside it.

                  One pair of figures for the person, drawn once and flush with the heading. Under
                  the account selector they would be indented to that control and take its meaning,
                  and moving the selector would move them: a customer holding two accounts would
                  read two running totals and two ceilings, which says a payment too large for the
                  day can be halved across two of their own accounts and go. It cannot.

                  The second line is worth its width. The total leaves out money that only moved
                  between the customer's own accounts, and that is not something anybody guesses.

                  WHAT IS NOT PRINTED is the smaller total above which the bank stops settling at
                  once and asks for a code. It is a fence, and a published fence has a gate in it.
                  The question a form has is about the payment in front of it, and the quote below
                  answers exactly that, per payment, before anything is sent.
                */}
                {today && accounts.length > 0 && (
                    <div className="section-block">
                        <div className="fact-line">
                            <span>
                                <span className="fact-label">{DAILY_OUTFLOW_LABEL.sentOut}:</span>{' '}
                                <span className="fact-value">{formatMoney(today.sentOut)}</span>
                            </span>
                            <span>
                                <span className="fact-label">{DAILY_OUTFLOW_LABEL.limit}:</span>{' '}
                                <span className="fact-value">{formatMoney(today.limit)}</span>
                            </span>
                        </div>
                        <p className="helper-text gap-above-sm">
                            Counted across every account you hold. Money moved between two of your
                            own accounts is not in it.
                        </p>
                    </div>
                )}

                {accounts.length > 0 && (
                    <form className="form" onSubmit={handleSubmit}>
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
                                    // Asked again with the account the payment will actually be
                                    // sent from. The ceiling behind the answer belongs to the
                                    // customer and does not move with this control, but a quote is
                                    // the bank's answer about a whole payment, and one standing
                                    // under a payload it was not asked about is a wrong number.
                                    void askForQuote(chosen, beneficiaryId, amount);
                                }}
                            >
                                {/* Grouped in fours like every other account number in this
                                    product: one unbroken run of twenty-four characters beside a
                                    grouped balance would be the only ungrouped identifier here. */}
                                {accounts.map((acc) => (
                                    <option key={acc.id} value={acc.id}>
                                        {formatIban(acc.iban)}
                                    </option>
                                ))}
                            </select>
                            <div className="field-side">
                                {ACCOUNT_LABEL.balance}: {formatMoney(selectedAccount?.balance)}
                            </div>
                            {/* Under the box it is about, on a line of its own: the row is a
                                wrapping flex line and the sentence claims the whole of the next
                                one. aria-describedby above ties the two together for a reader who
                                is not looking at either. */}
                            {fieldErrors.source && (
                                <p className="field-error" id={errorId('source')}>
                                    {fieldErrors.source}
                                </p>
                            )}
                        </div>

                        {/*
                          The saved payees, above the field they fill in, so cause sits above
                          effect: a name is chosen and the account number appears one row below.

                          An option carries the name and nothing else. The IBAN is about to be shown
                          in full underneath, and the trusted flag that decides whether a payment is
                          reviewed is not on this wire at all: a customer who can see which payee
                          escapes the check has been shown how to walk past it.

                          Absent entirely when the address book is empty. Creating a payee is out of
                          scope by owner decision 11, so an empty control would be furniture a
                          customer can never fill, on the one form that moves money.
                        */}
                        {beneficiaries.length > 0 && (
                            <div className="field-row">
                                <label className="field-label" htmlFor="payment-beneficiary">
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

                        {/* Quieter than the accounts failure, which takes the form down with it,
                            because this one takes nothing down: the payment can still be made by
                            typing the number. */}
                        {beneficiariesFailed && (
                            <p className="helper-text">
                                Saved beneficiaries could not be loaded. Enter an IBAN below.
                            </p>
                        )}

                        <div className="field-row">
                            <label className="field-label" htmlFor="payment-target">
                                To:
                            </label>
                            {/* Editable at all times, including while a payee is chosen. Made
                                read-only it would leave no way back to typing, since the control
                                that would give one does not exist. Touching it gives the choice up
                                silently: the customer has just named a different destination, and
                                saying so would be an argument about which of the two they meant. */}
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
                                    // Only where a choice is actually being given up. A typed
                                    // destination has no payee behind it and is never trusted, so
                                    // the answer about a code can change; the price cannot.
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
                          THE FIELD THE FORM TURNS ON, and it is drawn as the largest thing on the
                          screen: the rung the case pane next door spends on the two figures a
                          verdict turns on. Right aligned and tabular so the places line up as the
                          digits are typed, and the currency stated beside the box rather than
                          inside it, where it is not something anybody types.
                        */}
                        <div className="field-row">
                            <label className="field-label" htmlFor="payment-amount">
                                Amount:
                            </label>
                            {/* Stays type="text". A Czech amount is written `1 500,00`, which
                                type="number" refuses outright: it reports .value as the empty
                                string for anything it cannot interpret, so the field would go
                                blank on a perfectly good amount.

                                Normalized on blur rather than on every keystroke, because
                                rewriting while somebody is still typing moves the caret out from
                                under them, and half an amount is not yet an amount. */}
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
                                    // Half an amount is not an amount, and a price for the
                                    // previous one standing under it is a wrong number rather than
                                    // an old one.
                                    forgetQuote();
                                }}
                                onBlur={() => {
                                    const parsed = parseAmount(amount, readerLocale());
                                    if (parsed.ok) {
                                        setAmount(parsed.czech);
                                    }
                                    // Priced on blur for the reason the box is normalized on blur:
                                    // while somebody is still typing there is nothing settled to
                                    // price.
                                    void askForQuote(selectedAccountId, beneficiaryId, amount);
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
                          What the bank says this payment costs, before it is sent, indented to the
                          amount box rather than to the form so it reads as belonging to that row.

                          Three figures rather than one, because the fee is the number nobody could
                          see coming: the tariff is free below a threshold and charged above it, so
                          a heller past a boundary is ten crowns, and the total is what actually
                          leaves the account. The sentence under them is spoken only where there
                          will be a code: a line that appears on every payment has stopped being
                          read by the time it matters.

                          Silent while the review stands, which is the only time these three figures
                          would be on screen twice. Two readings of one price invite the reader to
                          look for the difference between them.
                        */}
                        {quote && !reviewing && (
                            <div className="under-field">
                                <div className="fact-line">
                                    {QUOTE_FIELDS.map((f) => (
                                        <span key={f}>
                                            <span className="fact-label">{QUOTE_LABEL[f]}:</span>{' '}
                                            <span className="fact-value">
                                                {formatMoney(quote[f])}
                                            </span>
                                        </span>
                                    ))}
                                </div>
                                {authorizationNote(quote.authorizationRequired) && (
                                    <p className="helper-text gap-above-sm">
                                        {authorizationNote(quote.authorizationRequired)}
                                    </p>
                                )}
                            </div>
                        )}

                        {/*
                          The quote refusing is worth saying. It prices a payment and does not
                          accept one, so nothing here is about the balance; what it does refuse is
                          an amount past the customer's daily ceiling, in the same words the submit
                          would use and before the money moves.

                          Kept as lines under the field rather than promoted to the box this window
                          draws a failure in: nothing has been sent, nothing is lost, and a bordered
                          panel would be louder than the price it stands in for.
                        */}
                        {quoteError && (
                            <div className="under-field">
                                {quoteError.lines.map((line, i) => (
                                    <p
                                        key={line}
                                        className={
                                            i === 0
                                                ? 'helper-text text-danger'
                                                : 'helper-text text-danger gap-above-sm'
                                        }
                                    >
                                        {line}
                                    </p>
                                ))}
                                {quoteError.retry && (
                                    <button
                                        type="button"
                                        className="btn btn--quiet gap-above-sm"
                                        onClick={quoteError.retry}
                                    >
                                        {quoteError.retryLabel}
                                    </button>
                                )}
                                {quoteError.reference && (
                                    <p className="summary-reference">{quoteError.reference}</p>
                                )}
                            </div>
                        )}

                        {/* The widest object on the screen and the quietest, which is the whole
                            statement about it: a reference line is optional and the amount above
                            is not. */}
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

                        {info.type === 'error' && (
                            <ErrorBox
                                failure={info.failure}
                                title="We could not send this payment"
                            />
                        )}

                        {info.type === 'success' && (
                            /* The payment exists in all three branches below: the bank took it,
                               and what is left to happen to it is what the words inside say. */
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
                                        Nothing has been taken from your account. You will be able
                                        to confirm this payment once the review is finished, and
                                        you can cancel it at any time from Waiting authorizations.
                                    </p>
                                )}
                                <ul>
                                    <li>Transfer: {formatTransferId(info.result.transferId)}</li>
                                    {/* The word carries its treatment here too. This panel is
                                        where a customer first learns a payment is under review
                                        rather than sent, and the two read alike in one colour. */}
                                    <li>
                                        Status:{' '}
                                        <span
                                            className={`tone-${transferStatusTone(
                                                info.result.status,
                                            )}`}
                                        >
                                            {transferStatusLabel(info.result.status, 'customer')}
                                        </span>
                                    </li>
                                    {/* chargedAmount is amount plus fee for every outcome, even
                                        ones where no money has moved, so the label may only claim
                                        a charge once the payment has settled. */}
                                    <li>
                                        {info.result.authorizationRequired ? (
                                            <>
                                                Will be charged after confirmation:{' '}
                                                {formatMoney(info.result.chargedAmount)}
                                            </>
                                        ) : (
                                            <>Charged: {formatMoney(info.result.chargedAmount)}</>
                                        )}
                                    </li>
                                    <li>Fee: {formatMoney(info.result.feeAmount)}</li>
                                    <li>
                                        {info.result.authorizationRequired ? (
                                            <>
                                                Current balance:{' '}
                                                {formatMoney(info.result.newBalance)}
                                            </>
                                        ) : (
                                            <>New balance: {formatMoney(info.result.newBalance)}</>
                                        )}
                                    </li>
                                    <li>
                                        Authorization required:{' '}
                                        {info.result.authorizationRequired ? 'YES' : 'NO'}
                                    </li>
                                </ul>
                            </div>
                        )}

                        {/*
                          THE STOP between a typed payment and a sent one, and it is a panel on the
                          form rather than a dialog over it, per owner decision 14. A modal would
                          have to be dismissed to re-read the boxes it is about; this stands under
                          them with every field frozen, so what is being confirmed and what was
                          typed are on screen together.

                          It is also the echo. The amount here is written the way the bank writes
                          it, so `1.500` typed by somebody who meant fifteen hundred is read back as
                          `1 500,00` before it is anybody's money.
                        */}
                        {stage.kind === 'review' ? (
                            <div className="summary summary--neutral gap-above-md">
                                <div className="summary-title">
                                    Check this payment before it is sent
                                </div>
                                <ul>
                                    {/* The payee's own name where one was chosen, because that is
                                        what the customer picked and the account number under it is
                                        the bank's spelling of it. */}
                                    <li>To: {reviewDestination}</li>

                                    {/* The price as the bank quoted it. Where the quote could not
                                        be got the amount is still printed, alone: a review that
                                        goes silent because a price is missing would be a stop that
                                        stops nothing. */}
                                    {quote ? (
                                        QUOTE_FIELDS.map((f) => (
                                            <li key={f}>
                                                {QUOTE_LABEL[f]}: {formatMoney(quote[f])}
                                            </li>
                                        ))
                                    ) : (
                                        <li>
                                            {QUOTE_LABEL.amount}: {stage.amountCzech} CZK
                                        </li>
                                    )}

                                    {/* Only where something was written. An empty line labelled
                                        Message would say a message was sent. */}
                                    {stage.payload.message && (
                                        <li>Message: {stage.payload.message}</li>
                                    )}
                                </ul>

                                {quote && authorizationNote(quote.authorizationRequired) && (
                                    <p className="helper-text gap-above-sm">
                                        {authorizationNote(quote.authorizationRequired)}
                                    </p>
                                )}

                                {/* The only submit on screen while this stands, which is what
                                    makes Enter finish the payment instead of asking for the review
                                    a second time. */}
                                <div className="summary-actions">
                                    <button
                                        type="submit"
                                        className="btn btn--primary"
                                        disabled={submitting}
                                    >
                                        {submitting ? 'Sending…' : 'Send now'}
                                    </button>
                                    {/* Quiet, and it decides nothing: it puts the form back the
                                        way it was, with every box still holding what was typed. */}
                                    <button
                                        type="button"
                                        className="btn btn--quiet"
                                        onClick={() => setStage({ kind: 'form' })}
                                        disabled={submitting}
                                    >
                                        Change this payment
                                    </button>
                                </div>
                            </div>
                        ) : (
                            <div className="actions">
                                {/* It no longer sends, so it no longer says it does. The word that
                                    names what this press does is on the button in the panel above,
                                    where the money actually moves. */}
                                <button type="submit" className="btn btn--primary">
                                    Review payment
                                </button>
                                {/*
                                  A draft is a thing you would press, so it is drawn as one: dashed
                                  rather than filled, and inert. A span and not a disabled button,
                                  because a button with no handler is a tab stop that answers
                                  nothing, and somebody working this form by keys would end on it.
                                  The pointer is told what it is, and the form is spared a sentence
                                  explaining what is not there.
                                */}
                                <span className="btn planned-item" title={PLANNED_TITLE}>
                                    Save as draft
                                </span>
                            </div>
                        )}
                    </form>
                )}
            </section>
        </main>
    );
}
