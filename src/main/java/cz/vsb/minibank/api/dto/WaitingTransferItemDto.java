package cz.vsb.minibank.api.dto;

/**
 * Item in the list of transfers that are waiting on the customer or on the bank.
 *
 * The destination is toIban, which is what TransferDetailsDto, TransferInfoDto and HistoryItemDto
 * have always called it. This record called the same field beneficiaryIban, and the name was
 * misleading as well as inconsistent: a payment typed straight into the IBAN box has no
 * beneficiary row behind it at all, so the field named one that does not exist for most rows.
 */
public record WaitingTransferItemDto(
        int id,
        String toIban,
        MoneyDto amount,
        String createdAt,
        String authMethod,
        // WAITING_AUTH or HELD_FOR_REVIEW. The list carries two kinds of row now - transfers
        // the customer can confirm, and transfers the bank is still reviewing - and without
        // this the second kind is indistinguishable from the first.
        String status
) {
}
