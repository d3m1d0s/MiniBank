package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.*;
import cz.vsb.minibank.application.FraudApplicationService;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertState;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.FeePolicy;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.NotFoundException;
import cz.vsb.minibank.domain.exceptions.ValidationException;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static cz.vsb.minibank.api.AuthHelpers.requireRole;
import cz.vsb.minibank.domain.UserRole;

/**
 * REST controller for fraud alert queue, details and analyst decisions.
 */
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
@RestController
@RequestMapping("/api/fraud")
public class FraudController {

    private final FraudAlertRepository alerts;
    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final FraudApplicationService fraudService;
    private final FeePolicy feePolicy;

    /**
     * Opens the unit of work the read endpoints below run inside.
     *
     * The write endpoint does not use it - {@code decide} goes through the application service,
     * which opens its own. The reads needed one because without it every repository call opens
     * and tears down its own connection: this project has no connection pool, and the queue made
     * one per alert.
     */
    private final UnitOfWorkFactory uowFactory;

    public FraudController(FraudAlertRepository alerts,
                           TransferRepository transfers,
                           AccountRepository accounts,
                           FraudApplicationService fraudService,
                           FeePolicy feePolicy,
                           UnitOfWorkFactory uowFactory) {
        this.alerts = alerts;
        this.transfers = transfers;
        this.accounts = accounts;
        this.fraudService = fraudService;
        this.feePolicy = feePolicy;
        this.uowFactory = uowFactory;
    }

    // -------------------------------------------------------------------------
    // Alert queue
    // -------------------------------------------------------------------------

    /**
     * Returns a filtered list of fraud alerts for the analyst queue.
     */
    @GetMapping("/alerts")
    public AlertQueueResponseDto listAlerts(
            @RequestParam(name = "state",       required = false) String state,
            @RequestParam(name = "minAmount",   required = false) BigDecimal minAmount,
            @RequestParam(name = "maxAmount",   required = false) BigDecimal maxAmount,
            @RequestParam(name = "createdFrom", required = false) String createdFrom,
            @RequestParam(name = "createdTo",   required = false) String createdTo,
            @RequestParam(name = "assignee",    required = false) String assignee,

            /*
             * Transfer statuses whose alerts the caller does not want to see. Repeatable, and
             * Spring also accepts one comma-separated value.
             *
             * An exclusion rather than an inclusion, which is the opposite of every other filter
             * here and deliberate. The screen's real question is "hide the ones I cannot act
             * on", and today that is exactly one status; expressing it as an inclusion means the
             * desk lists four of the five, and the day a sixth status is added it would vanish
             * from the analyst's default view without anyone touching the desk. For a tool whose
             * job is not to lose evidence, "forgot to list it" must mean shown, not hidden.
             */
            @RequestParam(name = "excludeTransferStatus", required = false)
            List<String> excludeTransferStatus
    ) {
        requireRole(UserRole.FRAUD_ANALYST);

        // Before anything is loaded: a range that cannot match is a mistake in the request, not
        // an empty result. Left unchecked, min above max returned an empty queue while the
        // counters below still reported non-zero totals, which reads as "no alerts match" rather
        // than as "your filter is backwards".
        requireUsableAmountRange(minAmount, maxAmount);

        // Parsed up here with the range check, so a filter the request got wrong is refused
        // before a connection is opened for it rather than after.
        Instant fromTs = parseInstant(createdFrom);
        Instant toTs = parseInstant(createdTo);
        Set<TransferStatus> hidden = parseTransferStatuses(excludeTransferStatus);

        // One unit of work for the whole read, and the reason is the shape of the loop below:
        // it asks for every alert and then for one transfer per alert. There is no connection
        // pool in this project, so outside a unit of work each of those calls opened and tore
        // down its own JDBC connection - N+1 of them to draw one screen. Inside one, they share
        // a connection and the identity map answers the second request for the same transfer.
        //
        // It buys connections and deduplication, not a consistent snapshot: nothing here sets an
        // isolation level, so at READ COMMITTED every statement still sees its own snapshot even
        // inside a transaction. An alert decided while this loop runs can still appear with its
        // old state.
        //
        // On the JSON backend the unit of work holds the store lock for the whole read, so
        // payments wait while a queue is drawn. Accepted: the loop is in memory and short, and
        // the alternative is the connection storm above on the backend that actually ships.
        try (UowScope scope = new UowScope(uowFactory.begin())) {
            return buildQueue(state, minAmount, maxAmount, fromTs, toTs, assignee, hidden);
        }
    }

    /**
     * Builds the queue. Called only from {@link #listAlerts}, inside its unit of work.
     */
    private AlertQueueResponseDto buildQueue(String state,
                                             BigDecimal minAmount,
                                             BigDecimal maxAmount,
                                             Instant fromTs,
                                             Instant toTs,
                                             String assignee,
                                             Set<TransferStatus> hidden) {
        List<FraudAlert> all = alerts.all();

        FraudAlertState stateFilter = parseState(state);

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
                continue;
            }

            Transfer t = optT.get();

            // Filtered on the transfer rather than on the alert, because that is where the fact
            // lives. An alert whose payment was withdrawn is still the analyst's to decide - the
            // verdict field is theirs and nothing here writes it - it is simply not urgent, and
            // a queue that cannot hide it fills up with rows that have nothing left to decide.
            if (hidden.contains(t.status())) {
                continue;
            }

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
                    // From the Transfer already loaded above for its amount, so no extra lookup.
                    t.status().name(),
                    amountStr,
                    currency,
                    alert.reason(),
                    createdAtStr,
                    alert.riskScore(),
                    alert.assignee()
            ));
        }

        // Over `all` rather than over `items`, deliberately: these describe the whole queue, and
        // the list beside them describes the filter. Both desks say so now, because seven over a
        // list of three reads as a contradiction until a screen names which number is which.
        //
        // Do not "fix" this to count the filtered list. State is itself one of the filters and
        // both desks open filtered to NEW, so two of the three would be permanently zero; and
        // the reason an analyst watches them at all is to see SUSPICIOUS rise as they work,
        // which a filtered count cannot show.
        long newCount = all.stream().filter(a -> a.state() == FraudAlertState.NEW).count();
        long suspiciousCount = all.stream().filter(a -> a.state() == FraudAlertState.SUSPICIOUS).count();
        long okCount = all.stream().filter(a -> a.state() == FraudAlertState.OK).count();

        AlertCountersDto counters = new AlertCountersDto(newCount, suspiciousCount, okCount);

        return new AlertQueueResponseDto(items, counters);
    }

    // -------------------------------------------------------------------------
    // Alert details
    // -------------------------------------------------------------------------

    /**
     * Returns detailed information about a fraud alert including transfer and account history.
     */
    @GetMapping("/alerts/{id}")
    public AlertDetailDto getAlert(@PathVariable("id") int id) {
        requireRole(UserRole.FRAUD_ANALYST);

        // Four lookups that used to be four connections: the alert, its transfer, that
        // transfer's account, and the account's whole history. Same reasoning as the queue.
        try (UowScope scope = new UowScope(uowFactory.begin())) {
            FraudAlert alert = alerts.byId(id)
                    .orElseThrow(() -> new NotFoundException("Fraud alert not found: " + id));

            // Both references below came from stored rows, not from the request.
            Transfer transfer = transfers.byId(alert.transferId())
                    .orElseThrow(() -> new DataIntegrityException(
                            "Fraud alert " + id + " points at missing transfer " + alert.transferId()));

            Account source = accounts.byId(transfer.sourceAccountId())
                    .orElseThrow(() -> new DataIntegrityException(
                            "Transfer " + transfer.id() + " points at missing account " + transfer.sourceAccountId()));

            AlertInfoDto alertDto = mapAlertInfo(alert);
            TransferInfoDto transferDto = mapTransferInfo(transfer, source);
            List<HistoryItemDto> history = mapHistoryForAccount(source.id());

            return new AlertDetailDto(alertDto, transferDto, history);
        }
    }

    // -------------------------------------------------------------------------
    // Alert decision
    // -------------------------------------------------------------------------

    /**
     * Applies a decision to a fraud alert (approve, decline or request customer confirmation).
     */
    @PostMapping("/alerts/{id}/decision")
    public AlertDetailDto decide(@PathVariable("id") int id,
                                 @RequestBody FraudDecisionRequest req) {
        requireRole(UserRole.FRAUD_ANALYST);

        // From the session, never from the body. FraudDecisionRequest deliberately has no
        // analyst field, for the reason NewPaymentRequest has no customerId: a field the caller
        // can set is one line away from being trusted, and this one becomes an audit record.
        // Not req.assignee() either - an assignee is who should look at an alert, decided_by is
        // who did. requireRole above has already proved there is a signed-in user.
        String analyst = AuthHelpers.requireUser().username();

        fraudService.decideAndUpdateAlert(
                id,
                req.decision(),
                req.reason(),
                req.assignee(),
                req.tags(),
                req.notes(),
                analyst
        );

        return getAlert(id);
    }


    // -------------------------------------------------------------------------
    // Mapping and utilities
    // -------------------------------------------------------------------------

    private AlertInfoDto mapAlertInfo(FraudAlert alert) {
        String createdAtStr = alert.createdAt() != null ? alert.createdAt().toString() : null;
        List<String> tags = alert.tags() != null ? alert.tags() : List.of();

        String resolvedAtStr = alert.resolvedAt() != null ? alert.resolvedAt().toString() : null;

        return new AlertInfoDto(
                alert.id(),
                alert.state().name(),
                alert.decision(),
                alert.decidedBy(),
                resolvedAtStr,
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
        // A14: what it was charged if it has settled, and only otherwise a quote from the
        // current policy. Recomputing this made the fraud desk restate what a customer was
        // charged last month whenever the FeePolicy bean was swapped.
        String feeStr = t.feeFor(feePolicy).amount().toPlainString();

        // Unguarded: a transfer always carries its creation instant. The authorization method
        // beside it genuinely may be absent, which is why only one of these two is a ternary.
        String createdAtStr = t.createdAt().toString();
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

    /**
     * Builds recent outgoing transfer history for the given account: newest first, at most ten.
     *
     * The sort used to compose a null rule and then reverse the whole comparator.
     * {@code Comparator.reversed()} is {@code Collections.reverseOrder(this)}, which swaps the
     * two arguments rather than negating the result, so it inverted the null placement along
     * with the order: a null would have sorted *first* under a rule that says last, and taken a
     * slot in the ten this panel shows. Reverse the key comparator inside if a null rule is ever
     * wanted here again - never the composed one outside.
     *
     * It sorts on the raw value now, because a transfer cannot carry a null creation instant:
     * {@code Transfer}'s constructor requires it, and the loader drops a null rather than
     * storing one. That makes this the one form that is not null-safe, and deliberately so - a
     * null here would mean the domain has been broken by an edit, and a fraud desk answering 500
     * is better than one quietly reordering the evidence.
     */
    private List<HistoryItemDto> mapHistoryForAccount(int accountId) {
        List<Transfer> list = transfers.bySourceAccount(accountId);

        list.sort(Comparator.comparing(Transfer::createdAt).reversed());

        return list.stream()
                .limit(10)
                .map(t -> new HistoryItemDto(
                        t.id(),
                        t.createdAt().toString(),
                        t.amount().amount().toPlainString(),
                        t.currency(),
                        t.status().name(),
                        t.targetIbanSnapshot(),
                        t.declineReason()
                ))
                .toList();
    }

    /**
     * Parses the state filter. An unknown value is caller input: FraudAlertState.valueOf
     * would raise IllegalArgumentException, which has no handler and answers 500.
     */
    /**
     * Reads the transfer statuses the caller wants hidden, refusing one it does not recognise.
     *
     * Refused rather than ignored, like every other filter here: a typo that silently widens the
     * queue tells the analyst they have seen everything when the filter they asked for was never
     * applied. An absent or empty parameter hides nothing, which is the API's default - a screen
     * may choose to hide withdrawn payments, an endpoint must not do it on its own.
     */
    private static Set<TransferStatus> parseTransferStatuses(List<String> values) {
        if (values == null || values.isEmpty()) return Set.of();

        Set<TransferStatus> parsed = EnumSet.noneOf(TransferStatus.class);
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            try {
                parsed.add(TransferStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ValidationException("Unknown transfer status filter: " + value);
            }
        }
        return parsed;
    }

    private static FraudAlertState parseState(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return FraudAlertState.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown alert state filter: " + value);
        }
    }

    /**
     * Parses an ISO instant filter. An unparseable value used to be dropped, which answered
     * 200 with a queue that did not match what the analyst asked for.
     */
    /**
     * Refuses an amount range no transfer could ever fall in.
     *
     * A negative bound and a reversed range are both refused, and for the same reason: neither
     * can match anything, and an empty queue is how this endpoint reports "nothing matched". The
     * analyst has no way to tell those apart from the answer alone, and the counters make it
     * worse by continuing to report the whole queue's totals beside the empty list.
     *
     * Both desks check the same thing before sending, which is the affordance - it names which
     * two numbers are the wrong way round, and this cannot, because no handler echoes an
     * exception message. This is the guard: the endpoint is reachable without either desk.
     */
    private static void requireUsableAmountRange(BigDecimal minAmount, BigDecimal maxAmount) {
        if (minAmount != null && minAmount.signum() < 0) {
            throw new ValidationException("minAmount must not be negative: " + minAmount);
        }
        if (maxAmount != null && maxAmount.signum() < 0) {
            throw new ValidationException("maxAmount must not be negative: " + maxAmount);
        }
        if (minAmount != null && maxAmount != null && minAmount.compareTo(maxAmount) > 0) {
            throw new ValidationException(
                    "minAmount " + minAmount + " is above maxAmount " + maxAmount);
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (Exception e) {
            throw new ValidationException("Unparseable timestamp filter: " + value);
        }
    }
}
