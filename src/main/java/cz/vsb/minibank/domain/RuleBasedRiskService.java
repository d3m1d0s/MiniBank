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
     * The bank-wide soft threshold on the day's total outflow: crossing it does not refuse the
     * payment, it makes the customer authorize it, exactly as the untrusted-and-high rule does.
     *
     * A default now, not a constant. It applies to any account with no soft_daily_threshold_czk
     * of its own. The paragraph that used to sit here arguing that a per-account value would
     * mean editing db/init/schema.sql is gone: that is this change.
     *
     * Strictly above, whatever the source: a day total of exactly the threshold still settles.
     * Same convention as AUTH_THRESHOLD_FOR_UNTRUSTED, whose strictness CreditLegTest.SETTLES_NOW
     * already depends on.
     */
    static final Money DEFAULT_SOFT_DAILY_THRESHOLD = Money.czk(15_000.00);

    /**
     * @param softDailyThreshold the account's own soft tier, or null to use
     *        {@link #DEFAULT_SOFT_DAILY_THRESHOLD}. An account whose ceiling is below the tier
     *        that applies to it has one tier and not two: every total that would reach the soft
     *        threshold has already been refused by requireWithinDailyLimit. That is now a
     *        fixture question with a fixture answer - see DemoScenario.SECONDARY_SOFT_THRESHOLD -
     *        rather than something no dataset could avoid.
     */
    @Override
    public RiskDecision evaluate(boolean beneficiaryTrusted, Money amount, Money sentSoFar,
                                 Money dailyLimit, Money softDailyThreshold) {
        requireWithinDailyLimit(amount, sentSoFar, dailyLimit);

        Money softTier = (softDailyThreshold != null) ? softDailyThreshold : DEFAULT_SOFT_DAILY_THRESHOLD;
        boolean overDayAuthThreshold = sentSoFar.plus(amount).gt(softTier);
        boolean untrustedAndHigh = !beneficiaryTrusted && amount.gt(AUTH_THRESHOLD_FOR_UNTRUSTED);

        boolean requireAuth = overDayAuthThreshold || untrustedAndHigh;
        // createAlert still strictly implies requireAuth here, but nothing depends on that any
        // more: routeTransferCreation tests createFraudAlert on its own and holds the transfer
        // for review, and the fraud service settles nothing, so these two thresholds can be
        // reordered without a money-path consequence. It used to be load-bearing - it was the
        // only reason the analyst's settle branch was unreachable.
        //
        // What this rule does NOT catch, and what the review gate therefore does not close: it
        // keys on one payment's amount, so 13 000 split into two payments of 6 500 to the same
        // untrusted IBAN raises no alert and is never held. A cumulative alert term, mirroring
        // what AUTH_THRESHOLD_FOR_DAY_TOTAL already does for authorization, is its own item.
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
