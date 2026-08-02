package cz.vsb.minibank.infrastructure.json.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * JSON representation of an account stored in the JSON data store.
 *
 * transferIds is gone with the domain field. Every store written before this change carries the
 * array, and JsonDataStore builds a plain ObjectMapper with FAIL_ON_UNKNOWN_PROPERTIES enabled,
 * so without the annotation below every existing data.json and demo.json - in a developer's
 * working tree or in a running deployment - would stop deserialising and JsonDataStore.load()
 * would throw at startup. A fresh clone is the one case that cannot break: data/ and storage/
 * are gitignored, so a clone has no store file and starts from an empty Bundle.
 *
 * Named narrowly rather than disabling FAIL_ON_UNKNOWN_PROPERTIES on the mapper. The global flip
 * would also start swallowing genuine typos in every other DTO, which is how a renamed field
 * becomes silent data loss. The stale arrays disappear from the files on their next save.
 *
 * There is no version field, and that is a decision rather than an omission. JsonUnitOfWork
 * holds the store lock from its constructor until commit or rollback, so a JSON transaction's
 * read and write cannot be interleaved and a version could never fail its comparison. Writing a
 * number nothing reads and nothing compares is precisely the defect this pass is correcting in
 * fraud_alerts.decision; creating a new instance of it in the same commit would be perverse.
 */
@JsonIgnoreProperties("transferIds")   // removed with A14/A6; stores written earlier still carry it
public class JsonAccount {
    public int id;
    public String iban;
    public double balance;
    public double dailyLimit;

    /**
     * This account's own soft authorization tier, or null to use the bank-wide one.
     *
     * Boxed rather than a primitive double, because 0.0 would mean "every payment crosses the
     * soft tier" and null has to be distinguishable from a real zero. A store written before
     * this change has the key absent, which Jackson leaves as null - the right answer.
     */
    public Double softDailyThreshold;
}
