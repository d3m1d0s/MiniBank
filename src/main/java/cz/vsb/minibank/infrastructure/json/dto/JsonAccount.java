package cz.vsb.minibank.infrastructure.json.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * JSON representation of an account stored in the JSON data store.
 *
 * transferIds is gone with the domain field, and dailyLimit and softDailyThreshold went the same
 * way when the two limits moved to the customer that holds the account - see Customer.dailyLimit
 * for why a ceiling per account was not a ceiling at all. Every store written before those changes
 * carries the stale keys, and JsonDataStore builds a plain ObjectMapper with
 * FAIL_ON_UNKNOWN_PROPERTIES enabled, so without the annotation below every existing data.json and
 * demo.json - in a developer's working tree or in a running deployment - would stop deserialising
 * and JsonDataStore.load() would throw at startup. A fresh clone is the one case that cannot break:
 * data/ and storage/ are gitignored, so a clone has no store file and starts from an empty Bundle.
 *
 * Named narrowly rather than disabling FAIL_ON_UNKNOWN_PROPERTIES on the mapper. The global flip
 * would also start swallowing genuine typos in every other DTO, which is how a renamed field
 * becomes silent data loss. The stale keys disappear from the files on their next save.
 *
 * A LIMIT THE STORE STILL CARRIES IS NOT READ, and that is the point of listing it here rather
 * than leaving the fields in place unused: the numbers on an old account row are the ones this
 * bank has stopped believing, and the customer row is now the only place either is written or
 * read.
 *
 * There is no version field, and that is a decision rather than an omission. JsonUnitOfWork
 * holds the store lock from its constructor until commit or rollback, so a JSON transaction's
 * read and write cannot be interleaved and a version could never fail its comparison. Writing a
 * number nothing reads and nothing compares is precisely the defect this pass is correcting in
 * fraud_alerts.decision; creating a new instance of it in the same commit would be perverse.
 */
// All three were removed from the aggregate; older stores still carry them.
@JsonIgnoreProperties({"transferIds", "dailyLimit", "softDailyThreshold"})
public class JsonAccount {
    public int id;
    public String iban;

    /**
     * Money is stored as the same type the domain holds it in. {@code Money} wraps a
     * {@link BigDecimal} normalized to two decimal places, so writing one out and reading it back
     * is a copy rather than a conversion, and Jackson parses the JSON number straight into it.
     *
     * These were {@code double} until this change. That was exact for every amount the bank can
     * hold - a heller-by-heller round trip over the whole range up to 100 000.00 loses nothing,
     * and the first loss is four orders of magnitude above what {@code NUMERIC(14,2)} on the SQL
     * side even accepts - so nothing was being lost. What it did cost is subtler and is the real
     * reason for the change: a primitive has no absent value, so a stored account with no
     * {@code balance} key deserialized to a silent 0.00. A reference type cannot do that, and
     * {@code JsonMapper} refuses the null instead.
     */
    public BigDecimal balance;
}
