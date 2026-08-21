package cz.vsb.minibank.api.dto;

/**
 * Lightweight view of an account for summaries.
 *
 * The three fields below the balance are what decides whether a payment out of this account is
 * refused and whether it is asked for a code, and until now the customer was shown none of them.
 * The two-tier behaviour therefore looked arbitrary from the outside: the same amount settled at
 * once in the morning and demanded a one-time code in the afternoon, with nothing on any screen to
 * say that the difference was the day's running total.
 *
 * @param dailyLimit         the hard ceiling on what may leave this account in one banking day,
 *                           fees excluded. Passing it is a refusal, not a challenge
 * @param softDailyThreshold the day total above which this account asks for a one-time code, or
 *                           null when it has no opinion of its own and the bank-wide tier applies.
 *                           Null is kept as null rather than resolved to the default here: the
 *                           account genuinely has no value, and a screen that printed the default
 *                           as if it were the account's would state as a property of the row a
 *                           number that moves when the bank changes its mind
 * @param spentToday         what has actually settled out of this account so far today, fees
 *                           excluded, on the banking day of Europe/Prague. It is the number both
 *                           limits above are measured against, so without it the other two are two
 *                           thresholds and no reading. Settled only: a payment waiting for its code
 *                           has moved nothing and is not counted, which is the same rule the
 *                           refusal itself applies
 */
public record AccountSummaryDto(
        int id,
        String iban,
        MoneyDto balance,
        MoneyDto dailyLimit,
        MoneyDto softDailyThreshold,
        MoneyDto spentToday
) {
}
