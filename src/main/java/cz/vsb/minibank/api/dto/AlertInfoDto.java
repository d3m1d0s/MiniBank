package cz.vsb.minibank.api.dto;

import java.util.List;

/**
 * Core fraud alert information used in detail screens.
 */
public record AlertInfoDto(
        int id,
        String state,
        // The verdict as recorded, who recorded it and when. Null on an alert nobody has
        // decided, and decidedBy is null on one decided from the console, which has no login.
        // On the wire because a column that is written and never readable is the defect this
        // pass is correcting, not one to create - even though no screen renders them yet.
        String decision,
        String decidedBy,
        String resolvedAt,
        String reason,
        Integer riskScore,
        String createdAt,
        String assignee,
        List<String> tags,
        String notes
) {
}
