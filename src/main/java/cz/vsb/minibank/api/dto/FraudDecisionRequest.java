package cz.vsb.minibank.api.dto;

import java.util.List;

public record FraudDecisionRequest(
        String decision,       // "APPROVE" | "DECLINE" | "REQUEST_CONFIRMATION"
        String reason,         // причина для DECLINE / комментарий
        String assignee,       // новый assignee (опционально)
        List<String> tags,     // новые теги (опционально)
        String notes           // заметки (опционально)
) {}
