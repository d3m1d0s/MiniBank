package cz.vsb.minibank.api.dto;

//TODO: only amount, createdAt and status are showing up on frontend
// right now, what is the problem? fix is needed
// (the same problem for backend?)
public record TransferDetailsDto(
        int id,
        String fromIban,
        String fromBalance,
        String toIban,
        String amount,
        String feeAmount,
        String status,
        String createdAt,
        String authMethod
) {}
