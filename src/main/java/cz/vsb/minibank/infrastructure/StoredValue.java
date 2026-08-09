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
}
