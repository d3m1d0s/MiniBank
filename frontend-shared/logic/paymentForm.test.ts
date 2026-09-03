import { describe, it, expect } from 'vitest';
import { checkPaymentForm } from './paymentForm';
import type {
    PaymentCheck,
    PaymentField,
    PaymentFormValues,
    PaymentProblems,
} from './paymentForm';

/**
 * The form's own refusals: all of them on one press, each at the box it is about.
 *
 * The behaviour under test is the plural. A form that stops at the first thing it finds sends the
 * customer round the loop once per mistake, and the two mistakes people actually make together are
 * an account they never chose and an amount typed in their own convention. What is checked here is
 * that both come back from one press, that each answer names its own box, and that the order the
 * caret is sent in is the order the boxes stand in rather than the order the checks ran in.
 *
 * The amount's own wording is not re-tested here; money.test.ts owns that. What is tested is that
 * the parser's reason is passed through as it was given rather than replaced by one generic
 * refusal, and that the value it read comes back with it so the caller need not parse twice.
 */

/** A form somebody has filled in correctly, which each case below then breaks in one place. */
const FILLED: PaymentFormValues = {
    selectedAccountId: 1,
    amount: '1 500,00',
    beneficiaryId: null,
    targetIban: 'CZ6508000000192000145399',
    hasSavedBeneficiaries: false,
};

/** The same form with every box left alone, which is what a first press on a fresh screen sees. */
const UNTOUCHED: PaymentFormValues = {
    selectedAccountId: null,
    amount: '',
    beneficiaryId: null,
    targetIban: '',
    hasSavedBeneficiaries: false,
};

const CZECH = 'cs-CZ';

/** The boxes a check complained about, named rather than counted. */
function complained(problems: PaymentProblems): PaymentField[] {
    return (Object.keys(problems) as PaymentField[]).sort();
}

/**
 * How Intl groups thousands in Czech, which is not the space anyone types.
 *
 * Spelled as an escape rather than pasted in, so that the assertions below say which character
 * they mean instead of carrying an invisible one.
 */
const NBSP = '\u00a0';

describe('a form with nothing wrong with it', () => {
    it('finds no problem, sends the caret nowhere and reads the amount', () => {
        const check: PaymentCheck = checkPaymentForm(FILLED, CZECH);
        expect(check.problems).toEqual({});
        expect(check.first).toBeNull();
        expect(check.amount).toEqual({ ok: true, value: 1500, czech: `1${NBSP}500,00` });
    });

    it('is just as happy with a saved payee and no typed destination', () => {
        const check = checkPaymentForm(
            { ...FILLED, beneficiaryId: 7, targetIban: '', hasSavedBeneficiaries: true },
            CZECH,
        );
        expect(check.problems).toEqual({});
    });
});

describe('each box on its own', () => {
    it('names the account when none is chosen', () => {
        const check = checkPaymentForm({ ...FILLED, selectedAccountId: null }, CZECH);
        expect(check.problems.source).toBe('Choose the account this payment leaves.');
        expect(check.problems.target).toBeUndefined();
        expect(check.problems.amount).toBeUndefined();
    });

    it('names the destination when neither half of it is given', () => {
        const check = checkPaymentForm({ ...FILLED, targetIban: '' }, CZECH);
        expect(check.problems.target).toBe('Enter the account number this payment goes to.');
    });

    it.each(['   ', '\t', '\n '])(
        'reads %o in the destination box as nothing given',
        (only) => {
            // An account number cannot be whitespace, and the request would go out with one.
            expect(checkPaymentForm({ ...FILLED, targetIban: only }, CZECH).problems.target)
                .toContain('Enter the account number');
        },
    );

    it('names the amount when the box is empty, in the parser own words', () => {
        const check = checkPaymentForm({ ...FILLED, amount: '' }, CZECH);
        expect(check.problems.amount).toBe('Enter the amount to send.');
    });

    it('passes the parser reason through rather than replacing it with one generic refusal', () => {
        // The point of passing it through: it teaches the shape. The amounts people get wrong are
        // the ones where two readings of the same characters differ by a factor of a thousand, and
        // "invalid amount" cannot say which reading this bank took.
        const check = checkPaymentForm({ ...FILLED, amount: 'a lot' }, CZECH);
        expect(check.problems.amount).toContain('1 500,00');
    });
});

describe('the destination sentence when the customer has saved payees', () => {
    it('offers the list as the other way out', () => {
        const check = checkPaymentForm(
            { ...FILLED, targetIban: '', hasSavedBeneficiaries: true },
            CZECH,
        );
        expect(check.problems.target).toBe(
            'Enter the account number this payment goes to, or choose a saved beneficiary.',
        );
    });

    it('does not offer a list to somebody who has none, which is an instruction they cannot follow', () => {
        const check = checkPaymentForm(
            { ...FILLED, targetIban: '', hasSavedBeneficiaries: false },
            CZECH,
        );
        expect(check.problems.target).not.toContain('saved beneficiary');
    });

    it('says nothing about the destination once a saved payee names it', () => {
        // Skipped explicitly rather than by luck. The two are never both sent: the server refuses
        // a request that names a beneficiary and an account number at once.
        const check = checkPaymentForm(
            { ...FILLED, beneficiaryId: 7, targetIban: '', hasSavedBeneficiaries: true },
            CZECH,
        );
        expect(check.problems.target).toBeUndefined();
    });
});

describe('more than one thing wrong at once', () => {
    it('reports all three from one press, which is the whole reason this function exists', () => {
        const check = checkPaymentForm(UNTOUCHED, CZECH);
        expect(complained(check.problems)).toEqual(['amount', 'source', 'target']);
    });

    it('reports the pair people actually make together', () => {
        // No account chosen and an amount in a convention this parser refuses. Answered one at a
        // time, that is two presses and two refusals for one filling in of one form.
        const check = checkPaymentForm(
            { ...FILLED, selectedAccountId: null, amount: 'abc' },
            CZECH,
        );
        expect(check.problems.source).toBeDefined();
        expect(check.problems.amount).toBeDefined();
    });
});

describe('where the caret is sent', () => {
    it('goes to the account, which stands above the other two', () => {
        expect(checkPaymentForm({ ...FILLED, selectedAccountId: null, amount: '' }, CZECH).first)
            .toBe('source');
    });

    it('goes to the destination when the account is chosen and the amount is not readable', () => {
        // Screen order, not check order. The amount is parsed second and stands third, so a form
        // that sent the caret in the order the checks ran would skip the box in between.
        expect(checkPaymentForm({ ...FILLED, targetIban: '', amount: '' }, CZECH).first)
            .toBe('target');
    });

    it('goes to the amount when it is the only thing left', () => {
        expect(checkPaymentForm({ ...FILLED, amount: '' }, CZECH).first).toBe('amount');
    });

    it('goes nowhere when there is nothing to fix', () => {
        expect(checkPaymentForm(FILLED, CZECH).first).toBeNull();
    });

    it('names a box that has a problem in it, every time', () => {
        // The trap on the other side: a first field that names a box with no sentence under it
        // moves the caret to a box the customer cannot see anything wrong with.
        for (const values of [
            UNTOUCHED,
            { ...FILLED, selectedAccountId: null },
            { ...FILLED, targetIban: '' },
            { ...FILLED, amount: '' },
        ]) {
            const check = checkPaymentForm(values, CZECH);
            const first = check.first;
            expect(first).not.toBeNull();
            expect(first == null ? undefined : check.problems[first]).toBeDefined();
        }
    });
});

describe('the amount the caller gets back', () => {
    it('is the reading, so the caller does not parse the same string a second time', () => {
        const check = checkPaymentForm({ ...FILLED, amount: '2 000,50' }, CZECH);
        expect(check.amount).toEqual({ ok: true, value: 2000.5, czech: `2${NBSP}000,50` });
    });

    it('carries the Czech spelling the box is rewritten with, so the reading is on screen', () => {
        // The echo. Whatever was understood goes back into the box, which is what stops the
        // customer having to guess which reading the bank took.
        expect(checkPaymentForm({ ...FILLED, amount: '1500' }, CZECH).amount)
            .toEqual({ ok: true, value: 1500, czech: `1${NBSP}500,00` });
    });

    it('reads the one ambiguous shape against the locale it was handed', () => {
        // `1,000` is a thousand to a reader whose locale groups with a comma. To a Czech one the
        // comma divides, which makes it three digits of hellers and refused for being finer than
        // the currency goes. Same characters, two readings, and the locale is what tells them
        // apart: the parser that took the second reading unconditionally paid 1 CZK for 1000.
        expect(checkPaymentForm({ ...FILLED, amount: '1,000' }, 'en-US').amount)
            .toEqual({ ok: true, value: 1000, czech: `1${NBSP}000,00` });
        expect(checkPaymentForm({ ...FILLED, amount: '1,000' }, CZECH).amount.ok).toBe(false);
    });

    it('comes back unreadable rather than absent, so the caller has one thing to test', () => {
        const check = checkPaymentForm({ ...FILLED, amount: 'abc' }, CZECH);
        expect(check.amount.ok).toBe(false);
    });

    it('is read even while another box is empty, the checks not stopping at the first', () => {
        const check = checkPaymentForm({ ...FILLED, selectedAccountId: null }, CZECH);
        expect(check.amount).toEqual({ ok: true, value: 1500, czech: `1${NBSP}500,00` });
    });
});
