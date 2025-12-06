package cz.vsb.minibank.api.dto;

import java.util.List;

public record AlertInfoDto(
        int id,
        String state,
        String reason,
        Integer riskScore,
        String createdAt,
        String assignee,
        List<String> tags,
        String notes
) {}
