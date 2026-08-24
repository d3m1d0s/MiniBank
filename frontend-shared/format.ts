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
 * authorized yet. Two words for one fact, and both spelled here so that neither is typed out at a
 * call site and drifts into `n/a` on one screen and `unknown` on the next.
 *
 * Which of the two a value takes is decided by the value, not by the shape it lands in. Where the
 * absence is itself a fact about the payment, the word follows the field into a table column as
 * well: a dash in the Auth column of a queue of unauthorized payments says the row failed to load
 * when nothing failed. The dash is for the column whose value should be there and is not.
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
 * Where a payment stands against its authorization window: still running, closed, or never given
 * one at all.
 *
 * Three outcomes and there is no fourth. `none` is not a weaker `closed`: a payment released from
 * a review hold comes back to the customer with the deadline deliberately cleared, because the
 * five minutes are the customer's time to type a code and not the analyst's time to reach a
 * queue. An absent instant therefore means there is nothing to run out, not something that
 * already has.
 *
 * A value that is not a date is `none` as well, for the same reason {@link render} prints such a
 * string as itself: the server owns this field, and a client that cannot read it may not invent a
 * deadline out of it. It leaves the screen saying no limit was set and the server deciding, which
 * is the harmless way round.
 */
export type AuthWindowState = 'open' | 'closed' | 'none';

/**
 * Which of the three, read against the instant the caller is drawing at.
 *
 * `now` is a parameter and not a call to the clock in here. That is what lets the decision be
 * tested as a function rather than as a screen, and it is also the honest shape: the screen holds
 * one instant per render and every part of that render has to agree about it.
 *
 * The boundary is the server's, down to which side the deadline itself falls on: the domain
 * counts a window as expired only strictly after the instant, so a code entered on the second is
 * still a code entered in time.
 *
 * THE TWO CLOCKS ARE NOT THE SAME CLOCK and nothing here pretends otherwise. This screen refuses
 * earlier or later than the bank by exactly the skew between them, and the customer whose machine
 * runs fast loses only an attempt the server would have refused anyway. That is also why nothing
 * that gets a customer OUT of a stuck payment may be gated on this: cancelling stays live whatever
 * this returns.
 */
export function authWindowState(
    authValidUntil: string | null | undefined,
    now: Date,
): AuthWindowState {
    const at = deadlineAt(authValidUntil);
    if (at === null) {
        return 'none';
    }
    return now.getTime() > at ? 'closed' : 'open';
}

/**
 * How much of the window is left, in words: `4 minutes 37 seconds`, or nothing to say.
 *
 * The empty string covers a payment with no window and one whose window has closed, the same way
 * the glossary's label functions answer a value they have nothing to say about: the sentence that
 * uses this is chosen by {@link authWindowState} first, and only the open branch asks.
 *
 * Rounded up rather than down, because this is a countdown and not an elapsed time. With 400
 * milliseconds left a floor prints `0 seconds` beside a Confirm button that still works; the
 * ceiling says `1 second`, which is what a person watching would say. A zero survives only at the
 * exact instant of the deadline, which is one render at most.
 *
 * A whole minute drops the seconds, so `5 minutes` rather than `5 minutes 0 seconds`.
 *
 * Above an hour there is nothing to say, and that is a decision rather than a gap. A countdown is
 * urgency: it earns its place while a person can still act on the number and reads as noise once
 * they cannot. The product's own window is five minutes and never comes near this, but a seeded
 * payment carries a long one so that the screen has something to be asked about whenever the
 * showcase is opened, and counted to the second that window printed `in 43190 minutes 51 seconds`
 * beside a date that had already said it better.
 */
const COUNTDOWN_CEILING_SECONDS = 60 * 60;

export function formatTimeLeft(authValidUntil: string | null | undefined, now: Date): string {
    const at = deadlineAt(authValidUntil);
    if (at === null) {
        return '';
    }

    const left = Math.ceil((at - now.getTime()) / 1000);
    if (left < 0 || left > COUNTDOWN_CEILING_SECONDS) {
        return '';
    }

    const minutes = Math.floor(left / 60);
    const seconds = left % 60;
    const parts: string[] = [];

    if (minutes > 0) {
        parts.push(count(minutes, 'minute'));
    }
    if (seconds > 0 || minutes === 0) {
        parts.push(count(seconds, 'second'));
    }

    return parts.join(' ');
}

function count(value: number, unit: string): string {
    return value === 1 ? `${value} ${unit}` : `${value} ${unit}s`;
}

/** The deadline as a number, or null where there is none this module can read. */
function deadlineAt(authValidUntil: string | null | undefined): number | null {
    if (!authValidUntil) {
        return null;
    }

    const at = new Date(authValidUntil).getTime();
    return Number.isNaN(at) ? null : at;
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
