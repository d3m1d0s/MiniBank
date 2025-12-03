package cz.vsb.minibank.api.dto;

public record NewPaymentRequest(
        int customerId,      // пока можно игнорить и использовать фиксированный
        int sourceAccountId,
        String targetIban,
        double amountCzk,
        String message
) {}
