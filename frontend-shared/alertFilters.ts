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
 * What is wrong with the filters, or null when they can be sent.
 *
 * This is the affordance, not the guard. It names which two values are the wrong way round,
 * which the server cannot do: FraudController refuses the same ranges, but no handler in this
 * project echoes an exception message, so over the wire it is only "the request contains invalid
 * or missing values". The server keeps the rules because the endpoint is reachable without this
 * screen; this keeps them because an analyst deserves to be told what to change.
 *
 * An empty queue is how the endpoint reports "nothing matched", so a filter that cannot match
 * anything must never reach it - otherwise a backwards range is indistinguishable from a quiet
 * afternoon, and the counters beside the list go on reporting the whole queue.
 *
 * TWO PAIRS, ONE FUNCTION. The queue is narrowed by an amount range and by a range of raising
 * times, and both go wrong the same way. It was called amountRangeProblem while it knew about one
 * of them, which is a name that quietly promises the other pair is somebody else's problem: a
 * screen that grows the date boxes would have had to write a second checker, and two checkers is
 * how the two pairs come to disagree about what a reversed range is.
 *
 * ONE COMPLAINT AT A TIME, and which one is not arbitrary. A box the browser could not read comes
 * first because it is the only complaint whose evidence is not on screen: the value that would
 * explain it is gone, so nothing else said about the filters can be trusted. The dates come next,
 * because they narrow what the amounts are even asked about. The reversed amounts come last, which
 * is also where the reader's eye is, on the pair they just typed.
 */
export function filterProblem(
    filters: AlertFilters,
    unreadable: AmountBoxes,
): string | null {
    if (unreadable.min || unreadable.max) {
        // The example is spelled the Czech way because that is what the box beside this sentence
        // writes back: the parser echoes 1 500,50 into the field whatever was typed. It used to
        // teach 1500.50, which the parser does accept but no screen in either application ever
        // shows, so the one sentence a mistyped bound gets was teaching the other convention.
        return 'That amount could not be read. Enter a number, like 1 500 or 1 500,50.';
    }

    // The parameters go to the server as instants and are parsed there with Instant.parse, which
    // takes nothing else: a bare 2026-08-30 is refused with the same opaque sentence a reversed
    // range gets. So the example spells out the shape rather than naming the standard.
    const from = readInstant(filters.createdFrom);
    const to = readInstant(filters.createdTo);

    if (from === 'unreadable') {
        return 'The earliest date could not be read. Use an instant, like 2026-08-30T00:00:00Z.';
    }
    if (to === 'unreadable') {
        return 'The latest date could not be read. Use an instant, like 2026-08-30T00:00:00Z.';
    }
    if (from !== null && to !== null && from > to) {
        return `The earliest date (${filters.createdFrom}) is after the latest (${filters.createdTo}).`;
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

/**
 * The instant a date box holds, null for a box nobody filled in, and the marker for one that
 * cannot be an instant at all.
 *
 * Three answers rather than a number and a NaN, because NaN compares false against everything: a
 * reversed pair with one unreadable half would pass the comparison below and be sent, which is the
 * silent wrong answer this whole module exists to refuse.
 *
 * Date.parse accepts more shapes than the server does, and the ones it accepts and the server
 * refuses are exactly the ones worth naming here, so the check is what the server's own parser
 * would do rather than what the browser will tolerate.
 */
function readInstant(raw: string | undefined): number | null | 'unreadable' {
    if (!raw) {
        return null;
    }
    // The trailing Z or an offset is what makes the string an instant rather than a local time,
    // and it is the half a date input never writes.
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/.test(raw)) {
        return 'unreadable';
    }

    const at = Date.parse(raw);
    return Number.isFinite(at) ? at : 'unreadable';
}
