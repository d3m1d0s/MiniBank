package cz.vsb.minibank.api.dto;

/**
 * Lightweight view of an account for summaries.
 */
public record AccountSummaryDto(
        int id,
        String iban,
        MoneyDto balance
) {
}
