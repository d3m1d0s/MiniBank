import type { AlertFilters } from './fraud';

/**
 * Whether the browser failed to read what was typed into each amount box.
 *
 * A number input reports an unreadable value as the empty string, which is what a cleared box
 * reports too. Without knowing which of the two happened, the query builder drops the parameter
 * and the analyst is handed the whole queue looking like a filtered one - a silent wrong answer
 * in place of a visible refusal.
 */
export interface AmountBoxes {
    min: boolean;
    max: boolean;
}

/**
 * What is wrong with the amount range, or null when it can be sent.
 *
 * This is the affordance, not the guard. It names which two numbers are the wrong way round,
 * which the server cannot do: FraudController refuses the same range, but no handler in this
 * project echoes an exception message, so over the wire it is only "the request contains invalid
 * or missing values". The server keeps the rule because the endpoint is reachable without this
 * screen; this keeps it because an analyst deserves to be told what to change.
 *
 * An empty queue is how the endpoint reports "nothing matched", so a range that cannot match
 * anything must never reach it - otherwise a backwards filter is indistinguishable from a quiet
 * afternoon, and the counters beside the list go on reporting the whole queue.
 */
export function amountRangeProblem(
    filters: AlertFilters,
    unreadable: AmountBoxes,
): string | null {
    if (unreadable.min || unreadable.max) {
        return 'That amount could not be read. Enter a number, like 1500 or 1500.50.';
    }

    const min = filters.minAmount ? Number(filters.minAmount) : null;
    const max = filters.maxAmount ? Number(filters.maxAmount) : null;

    if (min !== null && !Number.isFinite(min)) {
        return 'The smallest amount is not a number.';
    }
    if (max !== null && !Number.isFinite(max)) {
        return 'The largest amount is not a number.';
    }
    if (min !== null && min < 0) {
        return 'The smallest amount cannot be negative.';
    }
    if (max !== null && max < 0) {
        return 'The largest amount cannot be negative.';
    }
    if (min !== null && max !== null && min > max) {
        return `The smallest amount (${filters.minAmount}) is above the largest (${filters.maxAmount}).`;
    }

    return null;
}
