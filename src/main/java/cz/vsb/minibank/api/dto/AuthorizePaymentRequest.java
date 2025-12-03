package cz.vsb.minibank.api.dto;

public record AuthorizePaymentRequest(
        String otp
) {}
