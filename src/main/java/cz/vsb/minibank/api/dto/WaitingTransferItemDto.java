package cz.vsb.minibank.api.dto;

/**
 * Item in the list of transfers that are waiting for customer authorization.
 */
public record WaitingTransferItemDto(
        int id,
        String beneficiaryIban,
        String amount,
        String createdAt,
        String authMethod
) {
}
