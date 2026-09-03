package cz.vsb.minibank.api.dto.fraud;

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
        // What the analyst wrote when they took that decision, and a field of its own on this
        // record for the first time. It used to travel inside reason, appended behind a " | ",
        // so one line on the screen said both why the bank's rules were worried and what a person
        // concluded, with nothing to tell a reader which half was which. Null on an open alert
        // and on a verdict taken without a word.
        String decisionComment,
        // Why the alert was raised, and only that, now that the comment above has left it.
        String reason,
        Integer riskScore,
        String createdAt,
        String assignee,
        List<String> tags
) {
}
