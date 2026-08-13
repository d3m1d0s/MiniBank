package cz.vsb.minibank.infrastructure;

import cz.vsb.minibank.domain.exceptions.DataIntegrityException;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Reading a value a stored row must carry, and refusing the row when it does not.
 *
 * Shared by both adapters rather than written out in each, because the failure this closes was
 * precisely the two of them drifting: every site below used to swallow an unreadable value and
 * carry on with whatever the constructor had left behind. Four separate copies of a rule are four
 * chances for one of them to stay lenient.
 *
 * The posture is the one {@code Bootstrap} already takes with a store it cannot parse and the one
 * {@code Transfer}'s constructor takes with an amount it cannot accept: a row that contradicts the
 * domain is refused rather than loaded, and it is named when it is refused, so a corrupt store can
 * be found rather than merely suspected.
 *
 * The cost, accepted: on the JSON backend one bad row makes the whole store unloadable, since it is
 * read as a single document. That is what a corrupt file already does there.
 */
public final class StoredValue {

    private StoredValue() {
    }

    /**
     * The enum constant a stored string names, or a refusal that names the row and the string.
     *
     * What this replaced is worth stating, because it looked harmless. Both loaders wrapped the
     * parse and the hydrate call that followed it in one {@code catch (Exception ignored)}, so an
     * unreadable value did two things rather than one: the aggregate kept the constructor's
     * default - CREATED for a transfer, NEW for an alert, which reopens a closed alert - and
     * every other stored field in that same call was discarded with it. A transfer lost its
     * creation instant, its authorization method, its decline reason and its OTP state; an alert
     * lost its reason, risk score, assignee, tags and notes.
     */
    public static <E extends Enum<E>> E requiredEnum(Class<E> type, String stored,
                                                     String field, String kind, int id) {
        if (stored == null) {
            throw new DataIntegrityException("Stored " + kind + " " + id + " has no " + field);
        }
        try {
            return Enum.valueOf(type, stored);
        } catch (IllegalArgumentException e) {
            throw new DataIntegrityException("Stored " + kind + " " + id
                    + " has an unreadable " + field + ": " + stored);
        }
    }

    /**
     * The instant a stored string names, or a refusal that names the row and the string.
     *
     * Absent and unreadable are refused together on purpose. They used to arrive at the same
     * place by different routes - a null column, or a parse whose exception was swallowed into
     * null - and both then met a hydrate call that overwrote the field only when the value was
     * non-null. So the row kept the instant its constructor had stamped at load time: a creation
     * time that moved every time the row was read, and that sorts first in a list promising the
     * newest.
     */
    public static Instant requiredInstant(String stored, String field, String kind, int id) {
        if (stored == null) {
            throw new DataIntegrityException("Stored " + kind + " " + id + " has no " + field);
        }
        try {
            return Instant.parse(stored);
        } catch (DateTimeParseException e) {
            throw new DataIntegrityException("Stored " + kind + " " + id
                    + " has an unreadable " + field + ": " + stored);
        }
    }

    /**
     * The same, for the timestamps a legitimate row is allowed not to carry: null when there is no
     * string at all, and the same named refusal when there is one and it cannot be read.
     *
     * Three fields need this shape rather than {@link #requiredInstant}'s, and absent really is
     * their normal state: a transfer that has not settled, a transfer released from review with no
     * authorization deadline, an alert nobody has decided. What stood in for it was the opposite
     * error to the one requiredInstant closed. Each of the three was parsed inside a catch that
     * swallowed everything, justified by the comment beside it only for the absent case, so an
     * unreadable string arrived as null as well - and null is the most lenient reading each of
     * those three fields has. A corrupt authorization deadline turned the five-minute window a
     * customer has to type their code into no window at all.
     *
     * Splitting absent from unreadable is the whole point, and it is why the null check is here
     * rather than inside requiredInstant: the two are one refusal on a mandatory field and two
     * different answers on an optional one. The daily total needs the same rule for the day a row
     * counts under, and reads it through here rather than through the loader, so a row the sum
     * refuses is named exactly as the loader would have named it.
     */
    public static Instant presentInstantOrNull(String stored, String field, String kind, int id) {
        if (stored == null) {
            return null;
        }
        return requiredInstant(stored, field, kind, id);
    }
}
