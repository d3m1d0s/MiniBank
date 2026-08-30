package cz.vsb.minibank.api.dto;

/**
 * Decision payload submitted by a fraud analyst for a given fraud alert.
 *
 * TWO FIELDS LEFT THIS RECORD AND NEITHER IS COMING BACK, so the reason is written down here
 * rather than only in the change that removed them.
 *
 * {@code tags} was a field on the wire, a column in the database and a validation rule with
 * nothing anywhere that could produce one: no screen offered a tag input, no screen printed a tag,
 * and the only two callers sent back the empty list they had just been given. They did not even
 * send it the same way - one desk sent {@code []}, which the service read as "clear them", and the
 * other sent nothing, which it read as "leave them alone" - so one desk quietly wrote to the
 * column on every decision and the other never did. The column is still read on the alert detail,
 * so an alert tagged by any future producer still shows its tags; what is gone is the ability to
 * write them through this route.
 *
 * {@code assignee} left for a different reason: it now has a route of its own,
 * {@code POST /api/fraud/alerts/{id}/assignment} and its DELETE. Kept here it would be a second
 * writer of one field with the opposite convention about blank - this record's blank meant "leave
 * the assignee alone", and the assignment route's absence of one means "release it" - and the
 * desks were echoing the assignee they had read back on every decision, which is enough to
 * resurrect an assignment a colleague had cleared in the meantime.
 *
 * {@code notes} left too, and unlike the two above it was not removed but replaced. It used to
 * carry a whole replacement blob: whatever it held became the alert's notes, so the last analyst
 * to press a button overwrote everything a colleague had written, and nothing recorded who had
 * written what or when. {@code note} carries ONE entry to append instead, and the journal it is
 * appended to has no edit and no delete. The old name is deliberately NOT accepted as an alias:
 * a desk that has not been rebuilt would send the whole box back on every decision and each press
 * would file the same paragraph again as a new note.
 *
 * @param decision action to perform: "APPROVE", "DECLINE" or "ANNOTATE". The third was called
 *                 REQUEST_CONFIRMATION, a name for something it never did: it asks nobody for
 *                 anything, it records a comment and takes no decision. The old spelling is still
 *                 accepted so a client that has not been rebuilt keeps the route, and it is the
 *                 only reason it appears here.
 * @param comment  what the analyst wants recorded with this decision, on any of the three. It was
 *                 called {@code reason}, which is the name the response gives the sentence the
 *                 bank's own rules produced, and the two were being written into one column. The
 *                 old spelling is accepted as an alias because the field means exactly what it
 *                 always meant, so a desk that has not been rebuilt keeps working with no change
 *                 of behaviour. Absent or blank records nothing and clears nothing
 * @param note     one entry to append to the alert's journal, or absent to append none. Blank is
 *                 the same as absent: a journal entry that says nothing is not a fact about the
 *                 case
 */
public record FraudDecisionRequest(
        String decision,
        @com.fasterxml.jackson.annotation.JsonAlias("reason") String comment,
        String note
) {
}
