package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.exceptions.DailyLimitExceededException;
import cz.vsb.minibank.domain.value.Money;

/**
 * Service that evaluates transfer risk and produces a risk decision.
 */
public interface RiskService {

    /**
     * Evaluates risk for a potential transfer.
     *
     * Two of the totals below are measured over different sets of rows, and the inconsistency is
     * chosen rather than overlooked. sentSoFar is one ACCOUNT's day, because the two limits it is
     * compared against - dailyLimit and softDailyThreshold - are columns on that account, and a
     * total that included another account's payments could not be measured against either.
     * sentToPayeeSoFar is one CUSTOMER's day, because the thing it exists to catch is a payment
     * a customer split, and a customer holding two accounts defeats an account-keyed version of
     * it completely by sending half from each. Keeping the two scopes the same would mean
     * choosing which of those to give up; this way the model carries one seam that a reader meets
     * here, at the point where the six values sit side by side.
     *
     * @param beneficiaryTrusted whether the beneficiary is trusted
     * @param amount             amount of the transfer being attempted. It is not part of
     *                           sentSoFar and is added by this service
     * @param sentSoFar          what has already left the source account on the day this
     *                           transfer belongs to, fees excluded
     * @param sentToPayeeSoFar   what has already left ANY account this customer holds FOR THIS
     *                           DESTINATION on the same day, fees excluded, and on the same terms
     *                           as sentSoFar: the amount being attempted is not part of it.
     *                           Separate from sentSoFar because the two rules ask different
     *                           questions - one is about how much a customer may move out of one
     *                           account, the other about whether one payment has been split into
     *                           several to one new payee, from wherever the customer keeps money
     * @param dailyLimit         the source account's hard ceiling on one day's outflow
     * @param softDailyThreshold the source account's own soft authorization tier, or null to
     *                           apply the bank-wide default. A per-account value is what makes
     *                           the two tiers two tiers on an account whose ceiling is below the
     *                           bank-wide number, where the soft tier could otherwise never fire
     * @return decision describing required actions and risk level
     * @throws DailyLimitExceededException when sentSoFar plus amount passes dailyLimit
     */
    RiskDecision evaluate(boolean beneficiaryTrusted, Money amount, Money sentSoFar,
                          Money sentToPayeeSoFar, Money dailyLimit, Money softDailyThreshold);

    /**
     * Refuses a transfer that would take the day's outflow past the account's ceiling.
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
     * @throws DailyLimitExceededException when sentSoFar plus amount passes dailyLimit
     */
    void requireWithinDailyLimit(Money amount, Money sentSoFar, Money dailyLimit);
}
