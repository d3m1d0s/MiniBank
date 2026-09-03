/**
 * Everything a new payment can be refused for before the bank is asked, and all of it at once.
 *
 * Shared because it was written twice, character for character, including the three sentences: the
 * customer's form and the analyst workstation's form each carried their own copy of the same block.
 * The copies agreed on the day they were written. What they could not agree on afterwards is a
 * fourth check, or a reworded sentence, or the order the boxes are visited in.
 *
 * All the checks run and the verdict is taken after them. The form used to stop at the first thing
 * it found, so a customer with no account chosen and an unreadable amount fixed one, pressed again,
 * and was refused again for the other one. Each answer belongs at the box it is about; a single
 * sentence at the foot of the form cannot say which of three boxes it means.
 */

import { parseAmount, type ParsedAmount } from '../text/money';

/** The three boxes this form can refuse on its own, in the order they stand on the screen. */
export type PaymentField = 'source' | 'target' | 'amount';

/** Every problem the last press found, and never only the first of them. */
export type PaymentProblems = Partial<Record<PaymentField, string>>;

/** What the boxes hold. Nothing here is a screen concern: no refs, no setters, no busy flag. */
export interface PaymentFormValues {
    /** The account the money leaves, or null while nothing is chosen. */
    selectedAccountId: number | null;
    /** The amount box as typed, in whatever convention the person in front of it uses. */
    amount: string;
    /** A saved payee, or null when the destination is typed out instead. */
    beneficiaryId: number | null;
    /** The typed destination. Never sent together with a beneficiary: the server refuses both. */
    targetIban: string;
    /** Whether this customer has saved payees, which decides how the destination is asked for. */
    hasSavedBeneficiaries: boolean;
}

export interface PaymentCheck {
    problems: PaymentProblems;
    /**
     * The box the caret belongs in, or null when there is nothing to fix.
     *
     * The first box a reader coming down the form would have reached, which is not the first check
     * that ran: the amount is parsed second and stands third.
     */
    first: PaymentField | null;
    /**
     * The amount as it was read, whether or not it could be.
     *
     * Handed back rather than parsed again by the caller, because the caller needs both halves of
     * it: the number to send, and the Czech spelling to write into the box so that what the bank
     * understood is on screen rather than only in the review beneath it.
     */
    amount: ParsedAmount;
}

/** Screen order, which is what decides where the caret lands. */
const PAYMENT_FIELDS: readonly PaymentField[] = ['source', 'target', 'amount'];

export function checkPaymentForm(values: PaymentFormValues, locale: string): PaymentCheck {
    const problems: PaymentProblems = {};

    if (values.selectedAccountId == null) {
        problems.source = 'Choose the account this payment leaves.';
    }

    // The parser's own reason is passed through rather than replaced by one generic refusal. It
    // names what is wrong with this particular string, and the amounts people get wrong are the
    // ones where two readings of the same characters differ by a factor of a thousand.
    const amount = parseAmount(values.amount, locale);
    if (!amount.ok) {
        problems.amount = amount.reason;
    }

    // Skipped explicitly when a saved payee names the destination, rather than by luck. The
    // sentence changes with what this customer actually has: offering a saved beneficiary to
    // somebody who has none is an instruction they cannot follow.
    if (values.beneficiaryId == null && values.targetIban.trim() === '') {
        problems.target = values.hasSavedBeneficiaries
            ? 'Enter the account number this payment goes to, or choose a saved beneficiary.'
            : 'Enter the account number this payment goes to.';
    }

    const first = PAYMENT_FIELDS.find((field) => problems[field] != null) ?? null;
    return { problems, first, amount };
}
