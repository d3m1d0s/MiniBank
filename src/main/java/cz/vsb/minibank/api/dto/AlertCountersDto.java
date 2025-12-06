package cz.vsb.minibank.api.dto;

public record AlertCountersDto(
        long newCount,
        long suspiciousCount,
        long okCount
) {}
