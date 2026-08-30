package cz.vsb.minibank.infrastructure.json.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON representation of a customer with related accounts, beneficiaries and the two limits on
 * one day's spending.
 */
public class JsonCustomer {
    public int id;
    public String name;
    public String email;
    public JsonAddress address;
    public List<Integer> accountIds = new ArrayList<>();
    public List<JsonBeneficiary> beneficiaries = new ArrayList<>();

    /**
     * The hard ceiling on what may leave this customer in one day, fees excluded.
     *
     * A {@link BigDecimal} and not a primitive, for the reason {@link JsonAccount#balance} gives:
     * a primitive has no absent value, so a row written without the key would deserialize to a
     * silent 0.00 - here, to a customer who may spend nothing. JsonMapper refuses the null instead.
     *
     * It is stored on the customer and not on the account because it is a limit on a person; see
     * Customer.dailyLimit. A store written before the move carries it on each account instead, and
     * such a row is refused rather than read from the account it used to sit on: two accounts held
     * two independent allowances, and there is no honest way to fold them into one.
     */
    public BigDecimal dailyLimit;

    /**
     * This customer's own soft authorization tier, or null to use the bank-wide one.
     *
     * Null is a meaning here rather than an absence to tolerate: 0.00 would mean "every payment
     * crosses the soft tier", so the two cannot be collapsed. A store written before the field
     * existed has the key absent, which Jackson leaves as null - the right answer.
     */
    public BigDecimal softDailyThreshold;
}
