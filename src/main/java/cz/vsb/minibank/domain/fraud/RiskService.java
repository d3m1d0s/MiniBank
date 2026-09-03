package cz.vsb.minibank.domain.fraud;

import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.domain.customer.Customer;

/**
 * Service that evaluates transfer risk and produces a risk decision.
 */
public interface RiskService {

    /**
     * Evaluates risk for a potential transfer.
     *
     * Both totals below are one CUSTOMER's day, and the four values they are measured against
     * come off that same customer. This used to be two scopes and a documented seam: the day
     * total was one account's, because the ceiling and the soft tier were columns on an account
     * and no wider total could be measured against them. Moving both limits to {@link Customer}
     * removed the reason and with it the seam. What the account-keyed total cost is worth
     * recording, since the shape looked harmless: somebody holding two accounts had two
     * independent allowances, paid half out of each, and no evaluation ever saw more than half
     * of what they spent.
     *
     * @param beneficiaryTrusted whether the beneficiary is trusted
     * @param amount             amount of the transfer being attempted. It is not part of
     *                           sentOutSoFar and is added by this service. Added whatever the
     *                           destination is, including one of the customer's own accounts,
     *                           which is the one place this rule is stricter than the total it
     *                           extends: an internal move counts while it is being made and stops
     *                           counting once it has settled. Strict in the safe direction, and
     *                           the alternative - a flag saying the destination is the customer's
     *                           own - would let the ceiling be stepped around by a payment nobody
     *                           had to declare
     * @param sentOutSoFar       what has already left this CUSTOMER on the day this transfer
     *                           belongs to, fees excluded: sent out of any account they hold,
     *                           less what only moved to another account of theirs
     * @param sentToPayeeSoFar   what has already left any account this customer holds FOR THIS
     *                           DESTINATION on the same day, fees excluded, and on the same terms
     *                           as sentOutSoFar: the amount being attempted is not part of it.
     *                           Separate from sentOutSoFar because the two rules ask different
     *                           questions - one is about how much a customer may move out in a
     *                           day, the other about whether one payment has been split into
     *                           several to one new payee
     * @param dailyLimit         the customer's hard ceiling on one day's outflow
     * @param softDailyThreshold the customer's own soft authorization tier, or null to apply the
     *                           bank-wide default. A per-customer value is what makes the two
     *                           tiers two tiers for a customer whose ceiling is below the
     *                           bank-wide number, where the soft tier could otherwise never fire
     * @return decision describing required actions and risk level
     * @throws DailyLimitExceededException when sentOutSoFar plus amount passes dailyLimit
     */
    RiskDecision evaluate(boolean beneficiaryTrusted, Money amount, Money sentOutSoFar,
                          Money sentToPayeeSoFar, Money dailyLimit, Money softDailyThreshold);

    /**
     * Refuses a transfer that would take the day's outflow past the customer's ceiling.
     *
     * Split out of {@link #evaluate} because authorization has to ask this one question again -
     * two transfers can each pass at creation and breach the ceiling together once both are
     * authorized - while none of the other rules apply a second time: the authorization the
     * soft threshold asks for is the very act being performed.
     *
     * It throws rather than returning a flag on {@link RiskDecision} because a flag is exactly
     * what a caller can forget to read, which is how the old comparison came to have no
     * consequence at all.
     *
     * @throws DailyLimitExceededException when sentOutSoFar plus amount passes dailyLimit
     */
    void requireWithinDailyLimit(Money amount, Money sentOutSoFar, Money dailyLimit);
}
