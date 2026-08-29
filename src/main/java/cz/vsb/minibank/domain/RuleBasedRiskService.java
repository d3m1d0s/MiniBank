package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.exceptions.DailyLimitExceededException;
import cz.vsb.minibank.domain.value.Money;

/**
 * Rule based implementation of RiskService, using thresholds on the single amount for untrusted
 * beneficiaries and thresholds on the day's running total for the customer's own limits.
 */
public class RuleBasedRiskService implements RiskService {

    private static final Money AUTH_THRESHOLD_FOR_UNTRUSTED = Money.czk(5_000.00);
    private static final Money ALERT_THRESHOLD_FOR_UNTRUSTED = Money.czk(10_000.00);

    /**
     * The bank-wide soft threshold on the day's total outflow: crossing it does not refuse the
     * payment, it makes the customer authorize it, exactly as the untrusted-and-high rule does.
     *
     * A default now, not a constant. It applies to any customer with no soft_daily_threshold_czk
     * of its own. The paragraph that used to sit here arguing that a per-row value would mean
     * editing db/init/schema.sql is gone: that is this change.
     *
     * Strictly above, whatever the source: a day total of exactly the threshold still settles.
     * Same convention as AUTH_THRESHOLD_FOR_UNTRUSTED, whose strictness CreditLegTest.SETTLES_NOW
     * already depends on.
     */
    static final Money DEFAULT_SOFT_DAILY_THRESHOLD = Money.czk(15_000.00);

    /**
     * @param softDailyThreshold the customer's own soft tier, or null to use
     *        {@link #DEFAULT_SOFT_DAILY_THRESHOLD}. A customer whose ceiling is below the tier
     *        that applies to them has one tier and not two: every total that would reach the soft
     *        threshold has already been refused by requireWithinDailyLimit. That is a fixture
     *        question with a fixture answer rather than something no dataset could avoid.
     */
    @Override
    public RiskDecision evaluate(boolean beneficiaryTrusted, Money amount, Money sentOutSoFar,
                                 Money sentToPayeeSoFar, Money dailyLimit,
                                 Money softDailyThreshold) {
        requireWithinDailyLimit(amount, sentOutSoFar, dailyLimit);

        Money softTier = (softDailyThreshold != null) ? softDailyThreshold : DEFAULT_SOFT_DAILY_THRESHOLD;
        boolean overDayAuthThreshold = sentOutSoFar.plus(amount).gt(softTier);
        boolean untrustedAndHigh = !beneficiaryTrusted && amount.gt(AUTH_THRESHOLD_FOR_UNTRUSTED);

        boolean requireAuth = overDayAuthThreshold || untrustedAndHigh;
        // Cumulative, and keyed on the payee rather than on the account. It used to read
        // `amount.gt(ALERT_THRESHOLD_FOR_UNTRUSTED)`, so 13 000 split into two payments of 6 500
        // to the same untrusted IBAN raised no alert and was never held - the gap the review
        // gate could not close because nothing ever asked the question.
        //
        // Keyed on the payee and not on the account's day total, which was the cheaper option
        // and the wrong one. The reason this alert carries is fixed - see below, "New
        // beneficiary + high amount" - so a trigger that fires on an unrelated day total would
        // hold a small payment to a payee of ten years' standing under a reason that is false,
        // and TransferApplicationService then refuses the owner's own valid code. Keyed on the
        // payee, the reason is true whenever the rule fires.
        //
        // Totalled across every account the customer holds, which the caller decides and this
        // service only receives. The KEY was argued at length above; the SCOPE never was, and it
        // was simply the day total's, taken because that is what the mechanism beside it used.
        // It cost the same defect one size up: 13 000 split as 6 500 from each of two of the
        // customer's own accounts defeated the cumulative rule as completely as two payments
        // once defeated the single-amount one. The day total has since been widened the same
        // way and for the same reason, so the two now agree on whose day they measure.
        //
        // What it costs, stated rather than discovered: once 10 000 has gone to one untrusted
        // payee in a day, every later payment to THAT payee is held, however small and out of
        // whichever of the customer's accounts it is paid. That is
        // inherent to a cumulative threshold and is not what the destination key avoids - it
        // narrows which payments are affected, not the shape of the rule.
        //
        // createAlert no longer implies requireAuth. It used to, and nothing depended on it:
        // routeTransferCreation tests createFraudAlert on its own.
        boolean createAlert =
                !beneficiaryTrusted && sentToPayeeSoFar.plus(amount).gt(ALERT_THRESHOLD_FOR_UNTRUSTED);

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
    public void requireWithinDailyLimit(Money amount, Money sentOutSoFar, Money dailyLimit) {
        Money dayTotal = sentOutSoFar.plus(amount);
        if (dayTotal.gt(dailyLimit)) {
            throw new DailyLimitExceededException("Daily limit " + dailyLimit + " exceeded: "
                    + sentOutSoFar + " already sent that day plus " + amount + " requested");
        }
    }
}
