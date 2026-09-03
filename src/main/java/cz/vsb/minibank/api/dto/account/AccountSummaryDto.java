package cz.vsb.minibank.api.dto.account;
import cz.vsb.minibank.api.dto.common.MoneyDto;

/**
 * Lightweight view of an account for summaries.
 *
 * The daily ceiling and the day's total used to be here, one copy per account. They are facts
 * about the person holding the accounts and not about any one of them, so they now arrive once,
 * beside this list rather than inside it - see {@link DailyOutflowDto}.
 */
public record AccountSummaryDto(
        int id,
        String iban,
        MoneyDto balance
) {
}
