package cz.vsb.minibank.infrastructure.json.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON representation of a fraud alert and its metadata.
 */
public class JsonFraudAlert {
    public int id;
    public int transferId;
    public String state;

    /**
     * The analyst's verdict, who recorded it and when.
     *
     * All three are null while the alert is open, and decidedBy stays null for a decision taken
     * from the console, which has no login. Absent from every store written before this change,
     * which Jackson leaves as null - the right answer for an alert nobody decided, and for one
     * decided before the fields existed.
     */
    public String decision;
    public String decidedBy;
    public String resolvedAt;

    public String reason;
    public String createdAt;

    public Integer riskScore;
    public String assignee;
    public List<String> tags = new ArrayList<>();
    public String notes;
}
