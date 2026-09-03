package cz.vsb.minibank.api.dto.account;

import java.util.List;
import cz.vsb.minibank.api.dto.fraud.AlertQueueResponseDto;

/**
 * What {@code GET /api/me/accounts} answers: the caller's accounts, and the one day they share.
 *
 * The day is nested under its own name rather than flattened in beside the list, following
 * {@link AlertQueueResponseDto}: {@code sentOut} and {@code limit} sitting next to
 * {@code accounts} would read as numbers about the accounts, and they are numbers about the
 * person holding them.
 *
 * IT TRAVELS WITH THE ACCOUNTS RATHER THAN ON A ROUTE OF ITS OWN because the two are refreshed for
 * the same reason and would otherwise be refreshed apart. Every screen that reloads balances
 * reloads them because a payment has just moved money, and that same payment is what moved the
 * day's total. A second endpoint would leave a screen holding a fresh balance beside a day total
 * from before the payment, which is the one pair of numbers a customer will read against each
 * other.
 *
 * The list keeps the shape it had, so a caller reads {@code accounts} where it used to read the
 * body. What replaced a bare array is not decoration: an array has nowhere to put a fact about the
 * customer, which is how the day's ceiling came to be copied onto every account in the first place.
 */
public record MyAccountsResponseDto(
        List<AccountSummaryDto> accounts,
        DailyOutflowDto today
) {
}
