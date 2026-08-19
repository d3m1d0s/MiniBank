package cz.vsb.minibank.api.dto;

/**
 * High-level transfer overview used in list or summary views.
 *
 * {@code toIbanInBank} stands beside the account it is about, and it is the same derived fact the
 * history rows beneath it carry, stated by {@link HistoryItemDto#isToIbanInBank}. Whether the money
 * left the bank is the first thing that decides what can still be done about a payment, so the desk
 * should not have to read it off the row below the one it is looking at.
 */
public record TransferInfoDto(
        int id,
        String code,
        String status,
        String fromIban,
        MoneyDto fromBalance,
        String toIban,
        boolean toIbanInBank,
        MoneyDto amount,
        MoneyDto feeAmount,
        String createdAt,
        String authMethod
) {
}
