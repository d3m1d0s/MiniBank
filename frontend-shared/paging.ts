/**
 * A page of a list, the payee list beside the payment form, and the words at the foot of a list
 * that offers more.
 *
 * Five lists across two applications now arrive a page at a time: the alert queue, the list of
 * what its own filter is hiding, the payments beside an alert, the waiting transfers, and the
 * customer's own payment history. All of them grow the same control at their foot, a Show more
 * button and a line saying how much of the list is on screen, and a control that says one thing on
 * the web and another on the workstation is the divergence this directory exists to prevent. The
 * strings and the arithmetic are settled once, here; where the button sits and what it is skinned
 * like belongs to each idiom.
 *
 * The two interfaces below are the wire, and they are the TypeScript halves of PageDto and
 * BeneficiaryDto. Neither is fetched here: the fraud calls live in fraud.ts and the customer's
 * calls live in each application's api.ts, and this module stays pure so its functions can be
 * tested without a server.
 */

/**
 * A page of rows as the API sends one.
 *
 * `page` and `size` are the parameters that were asked for, echoed back by the same names, so the
 * next request is derived from the page that arrived rather than from the number of rows the
 * screen is holding. Those two numbers stop agreeing the moment a row arrives twice; see
 * {@link appendPage}.
 *
 * `total` counts every row behind the filters that were applied, not the rows in `items`, and it
 * is how the count line and the Show more button know there is more. There is deliberately no
 * hasMore flag: it would be one fact stated twice and the two halves can disagree.
 *
 * On the fraud queue the counters are NOT this number and must never be derived from it. They
 * count the whole queue before any filter, `total` counts what the filters matched, and they
 * arrive beside a page of this shape rather than inside it.
 */
export interface Page<T> {
    items: T[];
    page: number;
    size: number;
    total: number;
}

/**
 * A saved payee, as much of one as the browser is told.
 *
 * Three fields, and the missing one is the point. The server's Beneficiary also carries a trusted
 * flag, which is what decides whether a payment to this payee is held for review, and it is not
 * on this wire by owner decision: a customer shown which payee escapes the check has been handed
 * the way around it. It is not sent as false and not sent under another name, so there is nothing
 * for a screen to leak by accident.
 *
 * The IBAN arrives unspaced, as the account holds it. Group it with formatIban before printing,
 * and send back what the customer sees: the IBAN constructor on the server strips whitespace
 * before it validates, so a grouped value is a valid one.
 */
export interface Beneficiary {
    id: number;
    name: string;
    iban: string;
}

/**
 * What a request that names no size gets, and the largest one the server accepts.
 *
 * The same two numbers as PageDto.DEFAULT_SIZE and PageDto.MAX_SIZE. They are repeated here
 * rather than fetched because a client that asks for a size the server refuses gets an error box
 * where a list should be, and finding that out at run time is worse than writing 100 twice.
 */
export const DEFAULT_PAGE_SIZE = 25;
export const MAX_PAGE_SIZE = 100;

/** The control at the foot of a list that has more behind it. */
export const SHOW_MORE = 'Show more';

/**
 * What that control says while the next page is in flight.
 *
 * Not "Loading more", which is wider than the label it replaces and would move the button under
 * the pointer that just pressed it. The list's own first load hint is a different sentence on a
 * different part of the screen: showing it here would blank a list somebody is reading.
 */
export const SHOW_MORE_BUSY = 'Loading…';

/**
 * How much of the list is on screen, for the line beside the Show more button.
 *
 * "Showing: 25 of 137" while there is more, "Showing: 137" once there is not.
 *
 * It reads as a label with a value, because that is what it is standing next to: this string takes
 * the last cell of the counters strip, beside "New: 12" and "Cleared: 122", and the four cells have
 * to look like one row of the same kind of fact. The earlier wording was "Showing all 137", which
 * put a word where the other three cells put a colon and, worse, sat directly under "Whole queue,
 * 137" saying almost the same thing in almost the same words, so the two had to be read twice to
 * be told apart.
 *
 * The "of" form survives only where it carries information the reader cannot get otherwise: that
 * the list is cut and a button will lengthen it. Once everything matched is on screen there is no
 * second number worth printing, so the label stands with one.
 *
 * No trailing full stop, for the same reason: a label with a stop in a row of labels is the odd
 * one out.
 *
 * The count is a row count and is printed plain. It does not go through the money formatter,
 * which would group it the Czech way and, worse, print 10 as 10,00.
 *
 * A total that has shrunk under a list already held cannot make this line lie: rows are counted
 * as at least as many as there are, so "Showing 12 of 10" is unreachable. An empty list is the
 * screen's own sentence and never this one.
 */
export function showingLine(shown: number, total: number): string {
    const held = Math.max(0, Math.trunc(shown));
    const all = Math.max(held, Math.trunc(total));
    return held >= all ? `Showing: ${all}` : `Showing: ${held} of ${all}`;
}

/**
 * Whether the Show more button is drawn at all.
 *
 * Drawn or removed, never disabled: a control that cannot do anything still invites the press
 * that proves it.
 */
export function hasMore(shown: number, total: number): boolean {
    return Math.max(0, Math.trunc(shown)) < Math.trunc(total);
}

/**
 * The page to ask for next, counted from the page that arrived and not from the rows on screen.
 *
 * Deriving it from the row count (shown / size) is right until a row is dropped as a repeat, and
 * from then on it asks again for a page it already has. See {@link appendPage}.
 */
export function nextPage(last: Page<unknown>): number {
    return last.page + 1;
}

/**
 * The rows already held, plus the ones that just arrived and are not among them.
 *
 * Offset paging shifts. A payment created between the first request and the second pushes a row
 * from page one down onto page two, so page two hands back a row that is already on screen: React
 * logs a duplicate key warning and the customer sees one payment twice. Dropping it here costs a
 * set and makes the list honest; the row it displaced is genuinely lost until the screen is
 * opened again, which is the price of offset paging and not something a client can repair.
 */
export function appendPage<T extends { id: number }>(
    held: readonly T[],
    incoming: readonly T[],
): T[] {
    const seen = new Set(held.map((row) => row.id));
    const merged = held.slice();

    for (const row of incoming) {
        if (!seen.has(row.id)) {
            seen.add(row.id);
            merged.push(row);
        }
    }

    return merged;
}

/**
 * Writes page and size onto a query string being built, and hands it back so it can be used in
 * place.
 *
 * It takes the parameters rather than returning fresh ones because the alert queue already builds
 * six of its own before it gets here, and a second URLSearchParams to merge is how one of them
 * goes missing.
 *
 * The two values are clamped to what the server accepts. They come from code and not from
 * anything typed, so a size of zero is a mistake in a screen, and a clamped list is a better
 * answer to it than the validation error box the server would send instead of rows.
 */
export function applyPaging(
    params: URLSearchParams,
    page: number,
    size: number,
): URLSearchParams {
    params.set('page', String(Math.max(0, Math.trunc(page))));
    params.set('size', String(Math.min(MAX_PAGE_SIZE, Math.max(1, Math.trunc(size)))));
    return params;
}
