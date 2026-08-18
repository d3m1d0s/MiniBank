package cz.vsb.minibank.api.dto;

import java.util.List;

/**
 * Decision payload submitted by a fraud analyst for a given fraud alert.
 *
 * @param decision action to perform: "APPROVE", "DECLINE" or "ANNOTATE". The third was called
 *                 REQUEST_CONFIRMATION, a name for something it never did: it asks nobody for
 *                 anything, it saves the assignee, tags and notes and takes no decision. The old
 *                 spelling is still accepted so a client that has not been rebuilt keeps the
 *                 route, and it is the only reason it appears here.
 * @param reason   explanation for DECLINE or an optional comment
 * @param assignee new assignee to set on the alert, if provided
 * @param tags     replacement list of tags, if provided
 * @param notes    free form notes for future reference
 */
public record FraudDecisionRequest(
        String decision,
        String reason,
        String assignee,
        List<String> tags,
        String notes
) {
}
