package cz.vsb.minibank.api.dto;

import java.util.List;

/**
 * Response wrapper for the fraud alerts queue including items and counters.
 */
public record AlertQueueResponseDto(
        List<AlertQueueItemDto> items,
        AlertCountersDto counters
) {
}
