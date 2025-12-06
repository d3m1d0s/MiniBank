package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.*;
import cz.vsb.minibank.application.FraudApplicationService;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertState;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.FeePolicy;
import cz.vsb.minibank.domain.exceptions.DomainException;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.repository.AccountRepository;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static cz.vsb.minibank.api.AuthHelpers.requireRole;
import cz.vsb.minibank.domain.UserRole;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/fraud")
public class FraudController {

    private final FraudAlertRepository alerts;
    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final FraudApplicationService fraudService;
    private final FeePolicy feePolicy;

    public FraudController(FraudAlertRepository alerts,
                           TransferRepository transfers,
                           AccountRepository accounts,
                           FraudApplicationService fraudService,
                           FeePolicy feePolicy) {
        this.alerts = alerts;
        this.transfers = transfers;
        this.accounts = accounts;
        this.fraudService = fraudService;
        this.feePolicy = feePolicy;
    }

    // -------------------------------------------------------------------------
    // Очередь алертов
    // -------------------------------------------------------------------------

    @GetMapping("/alerts")
    public AlertQueueResponseDto listAlerts(
            @RequestParam(name = "state",       required = false) String state,
            @RequestParam(name = "minAmount",   required = false) BigDecimal minAmount,
            @RequestParam(name = "maxAmount",   required = false) BigDecimal maxAmount,
            @RequestParam(name = "createdFrom", required = false) String createdFrom,
            @RequestParam(name = "createdTo",   required = false) String createdTo,
            @RequestParam(name = "assignee",    required = false) String assignee
    ) {
        requireRole(UserRole.FRAUD_ANALYST);
        List<FraudAlert> all = alerts.all();

        // Фильтрация по state
        FraudAlertState stateFilter = null;
        if (state != null && !state.isBlank()) {
            stateFilter = FraudAlertState.valueOf(state.toUpperCase(Locale.ROOT));
        }

        Instant fromTs = parseInstantOrNull(createdFrom);
        Instant toTs = parseInstantOrNull(createdTo);

        String assigneeFilter = (assignee != null && !assignee.isBlank())
                ? assignee.trim().toLowerCase(Locale.ROOT)
                : null;

        List<AlertQueueItemDto> items = new ArrayList<>();

        for (FraudAlert alert : all) {
            if (stateFilter != null && alert.state() != stateFilter) {
                continue;
            }

            if (fromTs != null && alert.createdAt() != null && alert.createdAt().isBefore(fromTs)) {
                continue;
            }

            if (toTs != null && alert.createdAt() != null && alert.createdAt().isAfter(toTs)) {
                continue;
            }

            if (assigneeFilter != null) {
                String a = alert.assignee();
                if (a == null || !a.toLowerCase(Locale.ROOT).contains(assigneeFilter)) {
                    continue;
                }
            }

            Optional<Transfer> optT = transfers.byId(alert.transferId());
            if (optT.isEmpty()) {
                continue; // "битый" алерт - просто игнорируем
            }

            Transfer t = optT.get();
            BigDecimal amount = t.amount().amount();
            if (minAmount != null && amount.compareTo(minAmount) < 0) continue;
            if (maxAmount != null && amount.compareTo(maxAmount) > 0) continue;

            String amountStr = amount.toPlainString();
            String currency = t.currency();
            String createdAtStr = alert.createdAt() != null ? alert.createdAt().toString() : null;

            items.add(new AlertQueueItemDto(
                    alert.id(),
                    "ALERT-%d".formatted(alert.id()),
                    "TR-%d".formatted(t.id()),
                    alert.state().name(),
                    amountStr,
                    currency,
                    alert.reason(),
                    createdAtStr,
                    alert.riskScore(),
                    alert.assignee()
            ));
        }

        long newCount = all.stream().filter(a -> a.state() == FraudAlertState.NEW).count();
        long suspiciousCount = all.stream().filter(a -> a.state() == FraudAlertState.SUSPICIOUS).count();
        long okCount = all.stream().filter(a -> a.state() == FraudAlertState.OK).count();

        AlertCountersDto counters = new AlertCountersDto(newCount, suspiciousCount, okCount);

        return new AlertQueueResponseDto(items, counters);
    }

    // -------------------------------------------------------------------------
    // Детали алерта
    // -------------------------------------------------------------------------

    @GetMapping("/alerts/{id}")
    public AlertDetailDto getAlert(@PathVariable("id") int id) {
        requireRole(UserRole.FRAUD_ANALYST);
        FraudAlert alert = alerts.byId(id)
                .orElseThrow(() -> new DomainException("Fraud alert not found: " + id));

        Transfer transfer = transfers.byId(alert.transferId())
                .orElseThrow(() -> new DomainException("Transfer not found: " + alert.transferId()));

        Account source = accounts.byId(transfer.sourceAccountId())
                .orElseThrow(() -> new DomainException("Account not found: " + transfer.sourceAccountId()));

        AlertInfoDto alertDto = mapAlertInfo(alert);
        TransferInfoDto transferDto = mapTransferInfo(transfer, source);
        List<HistoryItemDto> history = mapHistoryForAccount(source.id());

        return new AlertDetailDto(alertDto, transferDto, history);
    }

    // -------------------------------------------------------------------------
    // Принятие решения по алерту
    // -------------------------------------------------------------------------

    @PostMapping("/alerts/{id}/decision")
    public AlertDetailDto decide(
            @PathVariable("id") int id,
            @RequestBody FraudDecisionRequest req
    ) {
        requireRole(UserRole.FRAUD_ANALYST);
        FraudAlert alert = alerts.byId(id)
                .orElseThrow(() -> new DomainException("Fraud alert not found: " + id));

        int transferId = alert.transferId();

        String raw = Optional.ofNullable(req.decision())
                .orElseThrow(() -> new DomainException("decision must be provided"));
        String decision = raw.trim().toUpperCase(Locale.ROOT);

        switch (decision) {
            case "APPROVE" -> fraudService.approve(transferId);
            case "DECLINE" -> {
                String reason = Optional.ofNullable(req.reason())
                        .filter(s -> !s.isBlank())
                        .orElse("Declined by fraud analyst");
                fraudService.decline(transferId, reason);
            }
            case "REQUEST_CONFIRMATION" -> fraudService.requestCustomerConfirmation(transferId);
            default -> throw new DomainException("Unsupported decision: " + raw);
        }

        // обновляем метаданные алерта (assignee/tags/notes), если переданы
        alerts.byId(id).ifPresent(updated -> {
            if (req.assignee() != null && !req.assignee().isBlank()) {
                updated.assignTo(req.assignee().trim());
            }
            if (req.tags() != null) {
                updated.replaceTags(req.tags().stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList()));
            }
            if (req.notes() != null) {
                updated.updateNotes(req.notes());
            }
            alerts.save(updated);
        });

        // возвращаем актуальные детали
        return getAlert(id);
    }

    // -------------------------------------------------------------------------
    // Маппинг и утилиты
    // -------------------------------------------------------------------------

    private AlertInfoDto mapAlertInfo(FraudAlert alert) {
        String createdAtStr = alert.createdAt() != null ? alert.createdAt().toString() : null;
        List<String> tags = alert.tags() != null ? alert.tags() : List.of();

        return new AlertInfoDto(
                alert.id(),
                alert.state().name(),
                alert.reason(),
                alert.riskScore(),
                createdAtStr,
                alert.assignee(),
                tags,
                alert.notes()
        );
    }

    private TransferInfoDto mapTransferInfo(Transfer t, Account source) {
        String fromIban = source.iban().value();
        String fromBalance = source.balance().amount().toPlainString();

        String amountStr = t.amount().amount().toPlainString();
        String currency = t.currency();
        String feeStr = feePolicy.compute(t.amount()).amount().toPlainString();

        String createdAtStr = t.createdAt() != null ? t.createdAt().toString() : null;
        String authMethod = (t.authMethod() != null ? t.authMethod().method() : null);

        return new TransferInfoDto(
                t.id(),
                "TR-%d".formatted(t.id()),
                t.status().name(),
                fromIban,
                fromBalance,
                t.targetIbanSnapshot(),
                amountStr,
                feeStr,
                currency,
                createdAtStr,
                authMethod
        );
    }

    private List<HistoryItemDto> mapHistoryForAccount(int accountId) {
        // История по исходящим переводам этого счёта
        List<Transfer> list = transfers.bySourceAccount(accountId);

        list.sort(Comparator.comparing(Transfer::createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed());

        return list.stream()
                .limit(10)
                .map(t -> new HistoryItemDto(
                        t.id(),
                        t.createdAt() != null ? t.createdAt().toString() : null,
                        t.amount().amount().toPlainString(),
                        t.currency(),
                        t.status().name(),
                        t.targetIbanSnapshot(),
                        t.declineReason()
                ))
                .toList();
    }

    private static Instant parseInstantOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
