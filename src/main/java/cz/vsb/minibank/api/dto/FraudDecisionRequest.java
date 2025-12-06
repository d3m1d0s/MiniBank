package cz.vsb.minibank.api.dto;

import java.util.List;

/**
 * Decision payload submitted by a fraud analyst for a given fraud alert.
 *
 * @param decision action to perform, for example "APPROVE", "DECLINE" or "REQUEST_CONFIRMATION"
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
