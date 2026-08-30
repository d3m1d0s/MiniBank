package cz.vsb.minibank.api.dto;

/**
 * How much of one day's allowance a customer has already spent, and how much they were given.
 *
 * ONE CUSTOMER, NOT ONE ACCOUNT, and that is the whole reason the record exists. The ceiling is a
 * limit on a person now - see {@code cz.vsb.minibank.domain.Customer.dailyLimit} - so a customer
 * holding two accounts has one day between them and not two days that add up. While these two
 * numbers were properties of an account they arrived once per account, and a screen printing them
 * beside each balance was showing a person two allowances, neither of which was theirs.
 *
 * {@code sentOut} is what has already settled and left the customer today, fees excluded, less
 * whatever only moved between accounts they hold themselves. It is the same total the rule refuses
 * the next payment on, read through {@code TransferApplicationService.sentOutTodayBy}, so what the
 * screen shows and what the bank enforces cannot come apart. A payment still waiting for its code
 * is not in it: nothing has moved, and the ceiling is a rule about money that has.
 *
 * {@code limit} is the hard ceiling. Strictly above refuses: a day total landing exactly on the
 * limit still settles, which is the convention the risk rules use throughout.
 *
 * WHAT IS NOT HERE is the soft tier, the total above which the bank asks for a one-time code
 * rather than refusing. A customer who has not been given one of their own falls to the bank-wide
 * default, and that default is not visible from this package, so sending the tier would mean
 * writing the number down a second time here. The one place that decides it stays
 * {@code RuleBasedRiskService}, and the answer a screen actually needs reaches it per payment, in
 * {@link PaymentQuoteDto#authorizationRequired} before the payment and in
 * {@link NewPaymentResultDto#authorizationRequired} after it.
 */
public record DailyOutflowDto(
        MoneyDto sentOut,
        MoneyDto limit
) {
}
