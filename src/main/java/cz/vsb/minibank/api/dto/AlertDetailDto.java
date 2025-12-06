package cz.vsb.minibank.api.dto;

import java.util.List;

/**
 * Detailed view of a fraud alert including transfer and account history.
 */
public record AlertDetailDto(
        AlertInfoDto alert,
        TransferInfoDto transfer,
        List<HistoryItemDto> history
) {
}
