package cz.vsb.minibank.api.dto;

public record AuthorizePaymentResult(
        int transferId,
        String status,
        String chargedAmount, // например "10525.00 CZK" (amount + fee) или null/"" если не списывали
        String newBalance,    // текущий баланс после операции (или тот же при DECLINED)
        String declineReason  // текст причины отказа, например "OTP failed" или null
) {}

