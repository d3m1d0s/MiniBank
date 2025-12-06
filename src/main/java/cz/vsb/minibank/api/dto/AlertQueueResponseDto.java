package cz.vsb.minibank.api.dto;

import java.util.List;

public record AlertQueueResponseDto(
        List<AlertQueueItemDto> items,
        AlertCountersDto counters
) {}
