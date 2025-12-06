package cz.vsb.minibank.api.dto;

/**
 * Request payload for authorizing a pending transfer with a one-time password.
 */
public record AuthorizePaymentRequest(
        String otp
) {
}
