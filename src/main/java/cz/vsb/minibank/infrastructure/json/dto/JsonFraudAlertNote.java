package cz.vsb.minibank.infrastructure.json.dto;

/**
 * JSON representation of one entry in an alert's notes journal.
 *
 * A TOP-LEVEL LIST IN THE BUNDLE rather than a list nested inside {@link JsonFraudAlert}, which
 * mirrors the fraud_alert_notes table on the other backend and, more importantly, puts the journal
 * out of reach of {@code JsonFraudAlertRepository.save}: that method replaces a stored alert with
 * a fresh record built from the aggregate, so anything living on the alert record and not on the
 * aggregate would be erased by the next assignment somebody made.
 *
 * {@code author} is null only on the entry carried over from the legacy single notes field, which
 * recorded none. {@code writtenAt} is an ISO instant, like every other instant this store holds.
 */
public class JsonFraudAlertNote {
    public int alertId;
    public String author;
    public String writtenAt;
    public String text;
}
