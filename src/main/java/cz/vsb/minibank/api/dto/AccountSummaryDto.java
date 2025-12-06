package cz.vsb.minibank.api.dto;

/**
 * Lightweight view of an account for summaries.
 * The balance is preformatted as a string so the client does not need to know the Money type.
 */
public record AccountSummaryDto(
        int id,
        String iban,
        String balance
) {
}
