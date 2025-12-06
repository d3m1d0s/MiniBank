package cz.vsb.minibank.api.dto;

/**
 * Result of a payment authorization or cancellation.
 *
 * @param transferId    identifier of the transfer
 * @param status        resulting transfer status (for example SENT or DECLINED)
 * @param chargedAmount formatted total debited amount including fee, or null if nothing was debited
 * @param newBalance    formatted account balance after the operation
 * @param declineReason explanation of why the operation was declined, if applicable
 */
public record AuthorizePaymentResult(
        int transferId,
        String status,
        String chargedAmount,
        String newBalance,
        String declineReason
) {
}
