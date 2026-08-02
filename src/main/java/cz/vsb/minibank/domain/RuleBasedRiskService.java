package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.exceptions.DailyLimitExceededException;
import cz.vsb.minibank.domain.value.Money;

/**
 * Rule based implementation of RiskService, using thresholds on the single amount for untrusted
 * beneficiaries and thresholds on the day's running total for the account's own limits.
 */
public class RuleBasedRiskService implements RiskService {

    private static final Money AUTH_THRESHOLD_FOR_UNTRUSTED = Money.czk(5_000.00);
    private static final Money ALERT_THRESHOLD_FOR_UNTRUSTED = Money.czk(10_000.00);

    /**
     * Soft threshold on the day's total outflow: crossing it does not refuse the payment, it
     * makes the customer authorize it, exactly as the untrusted-and-high rule does.
     *
     * The same number for every account, applied to each account separately - a customer with
     * several accounts gets this budget on each of them, exactly as they get a ceiling on each
     * of them. It is not a column on Account: the per-account value is the hard ceiling and it
     * already has one, and a second column would mean editing db/init/schema.sql, which A14 and
     * A6 change together.
     *
     * The two tiers are only two tiers on an account whose {@code dailyLimit} is above this
     * number. On an account whose ceiling is lower, any total that would reach this threshold
     * has already been refused, so the soft tier can never fire there and the rule degenerates
     * to the ceiling alone. That is a fixture question, not a code one - see
     * DemoScenario.SECONDARY_DAILY_LIMIT.
     *
     * Strictly above: a day total of exactly 15 000.00 still settles, and the soft tier starts
     * at 15 000.01. Same convention as AUTH_THRESHOLD_FOR_UNTRUSTED, whose strictness
     * CreditLegTest.SETTLES_NOW already depends on.
     */
    private static final Money AUTH_THRESHOLD_FOR_DAY_TOTAL = Money.czk(15_000.00);

    @Override
    public RiskDecision evaluate(boolean beneficiaryTrusted, Money amount, Money sentSoFar, Money dailyLimit) {
        requireWithinDailyLimit(amount, sentSoFar, dailyLimit);

        boolean overDayAuthThreshold = sentSoFar.plus(amount).gt(AUTH_THRESHOLD_FOR_DAY_TOTAL);
        boolean untrustedAndHigh = !beneficiaryTrusted && amount.gt(AUTH_THRESHOLD_FOR_UNTRUSTED);

        boolean requireAuth = overDayAuthThreshold || untrustedAndHigh;
        // The new term only widens requireAuth, so createAlert still strictly implies it: an
        // untrusted amount over 10 000 is also over 5 000. FraudApplicationService.approve
        // depends on that implication for sendApproved to stay unreachable.
        boolean createAlert = !beneficiaryTrusted && amount.gt(ALERT_THRESHOLD_FOR_UNTRUSTED);

        String reason = createAlert
                ? "New beneficiary + high amount"
                : (requireAuth ? "Above the daily authorization threshold or untrusted+high amount" : null);

        return new RiskDecision(requireAuth, createAlert, reason);
    }

    /**
     * Strictly above, like the thresholds: a customer may spend exactly the limit, and the
     * refusal starts one heller past it.
     */
    @Override
    public void requireWithinDailyLimit(Money amount, Money sentSoFar, Money dailyLimit) {
        Money dayTotal = sentSoFar.plus(amount);
        if (dayTotal.gt(dailyLimit)) {
            throw new DailyLimitExceededException("Daily limit " + dailyLimit + " exceeded: "
                    + sentSoFar + " already sent that day plus " + amount + " requested");
        }
    }
}
