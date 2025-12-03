package cz.vsb.minibank.api.dto;

public record AccountSummaryDto(
        int id,
        String iban,
        String balance // строкой, чтобы не лезть внутрь Money
) {}
