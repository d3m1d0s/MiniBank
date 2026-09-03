/**
 * What this application still decides about money, which is now only whose convention an
 * ambiguous amount is read in.
 *
 * The parser itself has moved to {@code frontend-shared/money.ts}. It was written twice: once
 * here, and once as readAmount inside the analyst workstation's fraud desk, because that
 * application cannot import this one's src/. Two readers for one convention is the defect the
 * shared directory exists to prevent, and money is the worst field to keep two of - `1,000` is
 * one thousand under one reading and one crown under the other, and the copies did not agree.
 *
 * The three names below are re-exported rather than imported straight from the shared module by
 * every call site, because this module is where this application reads and writes money and a
 * screen should not have to know which half of it is shared.
 */

export { formatMoney, formatCzech, parseAmount } from '@shared/text/money';
export type { ParsedAmount } from '@shared/text/money';

/**
 * The locale an ambiguous amount is read in.
 *
 * Only `1,234` needs it: the one string that is a valid number under both the Czech and the
 * English convention and means two different things under them. Everything the reader is shown
 * is Czech whatever this returns.
 *
 * It stays on this side of the shared boundary because it reads the browser, and the shared
 * modules are pure functions so that they can be tested without one. The parser takes the locale
 * as an argument for the same reason.
 */
export function readerLocale(): string {
    return navigator.language || 'cs-CZ';
}
