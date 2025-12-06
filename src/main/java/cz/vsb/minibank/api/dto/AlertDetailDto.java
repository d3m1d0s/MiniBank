package cz.vsb.minibank.api.dto;

import java.util.List;

public record AlertDetailDto(
        AlertInfoDto alert,
        TransferInfoDto transfer,
        List<HistoryItemDto> history
) {}
