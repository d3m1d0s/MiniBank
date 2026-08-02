package cz.vsb.minibank.api.dto;

/**
 * Request payload for creating a new outgoing payment.
 *
 * There is deliberately no customerId here. The paying customer comes from the session and
 * from nowhere else; a field a caller can set would be one line away from being trusted.
 */
public record NewPaymentRequest(
        int sourceAccountId,
        String targetIban,
        double amountCzk,
        String message
) {
}
