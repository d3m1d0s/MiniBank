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
 * @param decision action to perform: "APPROVE", "DECLINE" or "ANNOTATE". The third was called
 *                 REQUEST_CONFIRMATION, a name for something it never did: it asks nobody for
 *                 anything, it saves the notes and takes no decision. The old spelling is still
 *                 accepted so a client that has not been rebuilt keeps the route, and it is the
 *                 only reason it appears here.
 * @param reason   explanation for DECLINE or an optional comment
 * @param notes    free form notes for future reference
 */
public record FraudDecisionRequest(
        String decision,
        String reason,
        String notes
) {
}
