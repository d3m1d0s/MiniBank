package cz.vsb.minibank.api.dto;

/**
 * Request payload for creating a new outgoing payment.
 * The customerId field is kept for compatibility; in most flows
 * the authenticated customer is derived from the current session.
 */
public record NewPaymentRequest(
        int customerId,
        int sourceAccountId,
        String targetIban,
        double amountCzk,
        String message
) {
}
