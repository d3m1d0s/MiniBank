package cz.vsb.minibank.api.dto;

/**
 * The fraud analyst queue: one page of alerts, and the counters for the whole queue beside it.
 *
 * The two numbers in here answer different questions and neither may be computed from the other.
 * {@code alerts.total()} counts what the current filters matched, so the foot of the list can say
 * "Showing 25 of 137"; {@code counters} counts every alert in every state before any filter and
 * before any page, so the strip above the list keeps saying how much work exists while the analyst
 * looks at three rows of it.
 *
 * The page is nested under its own name rather than flattened into this record for the same reason:
 * {@code page}, {@code size} and {@code total} beside {@code counters} would read as numbers about
 * the queue, and exactly one of them is.
 */
public record AlertQueueResponseDto(
        PageDto<AlertQueueItemDto> alerts,
        AlertCountersDto counters
) {
}
