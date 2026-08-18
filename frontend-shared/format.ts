/**
 * How a timestamp, an identifier and an account number are written, on every screen of both
 * applications.
 *
 * These were written at the call site, six or seven times over, and every copy was slightly
 * different. `new Date(x).toLocaleString()` with no arguments follows the machine of whoever is
 * looking, so the same demonstration showed `13/08/2026, 8:52:20 PM` on one computer and
 * `13. 8. 2026 20:52:20` on the next; the queue printed `ALERT-2` while the details panel eight
 * lines below it printed `2`.
 *
 * The bank is Czech and the prose is English, so the numbers follow cs-CZ and nothing else does.
 * The locale is passed rather than left to the environment for the same reason the amount parser
 * takes one: a format nobody chose is a format that changes under you.
 */

/**
 * What is printed where a value is absent.
 *
 * A table with a gap in it reads as a table that failed to load, so an absent value is drawn
 * rather than left out. This is the data table convention and it stays a dash there.
 */
export const EMPTY_VALUE = '—';

/**
 * The same absence, written where the value sits in prose rather than in a column.
 *
 * A definition list is read as sentences, and `Auth method: —` there reads as a redaction: as
 * though something is known and withheld. Nothing is withheld, the payment simply has not been
 * authorized yet. Two words for one fact because the two shapes are read differently, and both
 * spelled here so that neither is typed out at a call site and drifts into `n/a` on one screen
 * and `unknown` on the next.
 */
export const NOT_RECORDED = 'not recorded';

/**
 * The bank's own clock.
 *
 * Every timestamp on this wire is an `Instant`, so it arrives in UTC with a trailing `Z` and
 * carries no zone of its own. Rendered in the machine's zone, a payment made at 20:52 in Ostrava
 * reads as 14:52 to anyone looking from New York, and the demonstration disagrees with itself
 * depending on where it is opened. A Czech bank states Czech times.
 */
const BANK_ZONE = 'Europe/Prague';

/**
 * One form, and it stops at the minute.
 *
 * The seconds were noise: nothing on either desk is decided by them, and they made every
 * timestamp four characters wider in tables that are already short of room.
 */
const DATE_TIME = new Intl.DateTimeFormat('cs-CZ', {
    day: 'numeric',
    month: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: BANK_ZONE,
});

const DATE_ONLY = new Intl.DateTimeFormat('cs-CZ', {
    day: 'numeric',
    month: 'numeric',
    year: 'numeric',
    timeZone: BANK_ZONE,
});

/**
 * Prints an instant as `13. 8. 2026 20:52`, or the empty value when there is none.
 *
 * A string that is not a date is printed as itself rather than as `Invalid Date`. The server
 * owns this field and a client that cannot read it should say what arrived, not hide it behind
 * a word about JavaScript.
 */
export function formatDateTime(iso: string | null | undefined): string {
    return render(iso, DATE_TIME);
}

/** Prints an instant as `13. 8. 2026`, for a column where the time of day decides nothing. */
export function formatDate(iso: string | null | undefined): string {
    return render(iso, DATE_ONLY);
}

function render(iso: string | null | undefined, format: Intl.DateTimeFormat): string {
    if (!iso) {
        return EMPTY_VALUE;
    }

    const at = new Date(iso);
    return Number.isNaN(at.getTime()) ? iso : format.format(at);
}

/**
 * The code an alert is known by: `ALERT-2`.
 *
 * The server already builds this string for the queue and sends it as `alertCode`, so prefer the
 * field when a response carries one. This exists for the screens that hold nothing but the
 * numeric id and were therefore printing `Alert: 2` beside a queue row that said `ALERT-2` for
 * the same alert.
 */
export function formatAlertId(id: number): string {
    return `ALERT-${id}`;
}

/** The code a transfer is known by: `TR-9`. Same rule as {@link formatAlertId}. */
export function formatTransferId(id: number): string {
    return `TR-${id}`;
}

/**
 * Groups an account number in fours: `CZ65 0800 0000 1920 0014 5399`.
 *
 * This is how an IBAN is printed on paper and it is the only presentation rule the standard
 * gives. Two things follow from it here. A reader can check one group against a statement
 * instead of counting twenty-four unbroken characters; and the spaces are break opportunities,
 * which is what lets an IBAN sit in a narrow column without forcing the column open, since the
 * bare form has none and wraps only where a stylesheet is told to break anywhere.
 *
 * Whatever grouping arrived is discarded first, so a value that is already spaced is not spaced
 * twice.
 */
export function formatIban(iban: string | null | undefined): string {
    if (!iban) {
        return EMPTY_VALUE;
    }

    const compact = iban.replace(/\s+/g, '');
    return compact.replace(/(.{4})(?=.)/g, '$1 ');
}
