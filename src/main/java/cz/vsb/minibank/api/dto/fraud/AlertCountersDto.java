package cz.vsb.minibank.api.dto.fraud;

/**
 * Aggregate counters for fraud alerts by state.
 */
public record AlertCountersDto(
        long newCount,
        long suspiciousCount,
        long okCount
) {
}
