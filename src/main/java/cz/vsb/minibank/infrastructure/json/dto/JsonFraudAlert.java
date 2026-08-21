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

    /**
     * What the analyst wrote when they decided it, on any of the three decisions.
     *
     * Absent on every store written before this change, which Jackson leaves as null. Until now
     * the comment was appended into {@code reason} behind a " | ", so a legacy record carries it
     * there; {@code JsonMapper.toDomain} splits it out on load, exactly as the SQL migration does
     * for its own rows, and this field is what it splits it into.
     */
    public String decisionComment;

    public String reason;
    public String createdAt;

    public Integer riskScore;
    public String assignee;
    public List<String> tags = new ArrayList<>();

    /**
     * The single notes text this record used to carry, kept only so that a store written before
     * the journal existed can still be read.
     *
     * NOTHING WRITES IT ANY MORE. {@code JsonDataStore} carries whatever it finds here into the
     * journal - {@link JsonFraudAlertNote}, a list of its own beside the alerts - as the store is
     * read, and clears it, which is this backend's half of the migration the SQL side does with
     * an ALTER.
     * It stays declared because deleting the field would make Jackson drop the text of a legacy
     * store on the floor before anything had the chance to carry it.
     */
    public String notes;
}
