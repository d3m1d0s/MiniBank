package cz.vsb.minibank.api.dto;

/**
 * Item in the list of transfers that are waiting on the customer or on the bank.
 */
public record WaitingTransferItemDto(
        int id,
        String beneficiaryIban,
        String amount,
        String createdAt,
        String authMethod,
        // WAITING_AUTH or HELD_FOR_REVIEW. The list carries two kinds of row now - transfers
        // the customer can confirm, and transfers the bank is still reviewing - and without
        // this the second kind is indistinguishable from the first.
        String status
) {
}
