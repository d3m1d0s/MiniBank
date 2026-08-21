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
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static cz.vsb.minibank.api.AuthHelpers.requireRole;
import cz.vsb.minibank.domain.UserRole;

/**
 * REST controller for fraud alert queue, details and analyst decisions.
 */
@RestController
@RequestMapping("/api/fraud")
public class FraudController {

    private final FraudAlertRepository alerts;
    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final CustomerRepository customers;
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
                           CustomerRepository customers,
                           FraudApplicationService fraudService,
                           FeePolicy feePolicy,
                           UnitOfWorkFactory uowFactory) {
        this.alerts = alerts;
        this.transfers = transfers;
        this.accounts = accounts;
        this.customers = customers;
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
            List<String> excludeTransferStatus,

            /*
             * Which page of the filtered queue, and how many rows it holds. Absent means the first
             * page at PageDto.DEFAULT_SIZE, which is what a caller that has never heard of paging
             * gets - and it is now a bounded answer rather than the whole store.
             */
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        requireRole(UserRole.FRAUD_ANALYST);

        // Before anything is loaded: a range that cannot match is a mistake in the request, not
        // an empty result. Left unchecked, min above max returned an empty queue while the
        // counters below still reported non-zero totals, which reads as "no alerts match" rather
        // than as "your filter is backwards".
        requireUsableAmountRange(minAmount, maxAmount);

        // Parsed up here with the range check, so a filter the request got wrong is refused
        // before a connection is opened for it rather than after.
        FraudAlertRepository.QueueFilter filter = new FraudAlertRepository.QueueFilter(
                parseState(state),
                parseInstant(createdFrom),
                parseInstant(createdTo),
                assignee,
                minAmount,
                maxAmount,
                parseTransferStatuses(excludeTransferStatus));

        int pageIndex = requirePage(page);
        int pageSize = requireSize(size);
        int offset = requireReachableOffset(pageIndex, pageSize);

        // One unit of work for the whole read. It is three statements now rather than N+1: the
        // page, its total, and the counters. There is no connection pool in this project, so a
        // repository call made outside a unit of work opens a JDBC connection and tears it down
        // again, and the queue used to make one of those per alert - it asked for every alert and
        // then for the payment behind each one. Inside one unit of work the three share a
        // connection, and the identity map answers for an alert this transaction already holds.
        //
        // It buys connections and deduplication, not a consistent snapshot: nothing here sets an
        // isolation level, so at READ COMMITTED every statement still sees its own snapshot even
        // inside a transaction. The page and the counters beside it can therefore be a moment
        // apart, which is what a queue an analyst is working through looks like anyway.
        //
        // On the JSON backend the unit of work holds the store lock for the whole read, so
        // payments wait while a queue is drawn. Accepted: the work is in memory and short, and
        // the alternative is the connection storm above on the backend that actually ships.
        try (UowScope scope = new UowScope(uowFactory.begin())) {
            return buildQueue(filter, pageIndex, pageSize, offset);
        }
    }

    /**
     * Builds the queue. Called only from {@link #listAlerts}, inside its unit of work.
     */
    private AlertQueueResponseDto buildQueue(FraudAlertRepository.QueueFilter filter,
                                             int page,
                                             int size,
                                             int offset) {

        List<FraudAlertRepository.QueueRow> rows = alerts.queuePage(filter, offset, size);
        int total = alerts.queueTotal(filter);

        List<AlertQueueItemDto> items = rows.stream()
                .map(FraudController::mapQueueItem)
                .toList();

        // Over the whole queue rather than over the page or the filter, deliberately: these
        // describe how much work exists, and the list beside them describes what is being looked
        // at. Both desks say so now, because seven over a list of three reads as a contradiction
        // until a screen names which number is which.
        //
        // Do not "fix" this to count the filtered list or the page. State is itself one of the
        // filters and both desks open filtered to NEW, so two of the three would be permanently
        // zero; and the reason an analyst watches them at all is to see SUSPICIOUS rise as they
        // work, which neither a filtered nor a paged count can show.
        Map<FraudAlertState, Integer> byState = alerts.countByState();

        AlertCountersDto counters = new AlertCountersDto(
                byState.getOrDefault(FraudAlertState.NEW, 0),
                byState.getOrDefault(FraudAlertState.SUSPICIOUS, 0),
                byState.getOrDefault(FraudAlertState.OK, 0));

        return new AlertQueueResponseDto(new PageDto<>(items, page, size, total), counters);
    }

    private static AlertQueueItemDto mapQueueItem(FraudAlertRepository.QueueRow row) {
        FraudAlert alert = row.alert();
        String createdAtStr = alert.createdAt() != null ? alert.createdAt().toString() : null;

        return new AlertQueueItemDto(
                alert.id(),
                "ALERT-%d".formatted(alert.id()),
                "TR-%d".formatted(row.transferId()),
                alert.state().name(),
                // The payment's status and its amount came off the same row as the alert, which
                // is what replaced a lookup per alert.
                row.transferStatus().name(),
                MoneyDto.of(row.amount()),
                alert.reason(),
                createdAtStr,
                alert.riskScore(),
                alert.assignee()
        );
    }

    /**
     * The page index a request named, defaulting to the first.
     *
     * Refused rather than clamped when it is negative, for the reason every filter here is
     * refused rather than ignored: a request nobody honoured must not answer 200 with a list that
     * is not the one that was asked for.
     */
    private static int requirePage(Integer page) {
        if (page == null) return 0;
        if (page < 0) {
            throw new ValidationException("page must not be negative: " + page);
        }
        return page;
    }

    /**
     * The page size a request named, bounded at both ends.
     *
     * The ceiling is the point. An unbounded size re-opens the very thing paging exists to close,
     * because "give me everything" would still be one request away.
     */
    private static int requireSize(Integer size) {
        if (size == null) return PageDto.DEFAULT_SIZE;
        if (size < 1 || size > PageDto.MAX_SIZE) {
            throw new ValidationException(
                    "size must be between 1 and " + PageDto.MAX_SIZE + ": " + size);
        }
        return size;
    }

    /**
     * How many rows to skip to reach the page that was asked for, refusing one that cannot be
     * counted to.
     *
     * Widened before it is multiplied, because size is bounded above and page is bounded only by
     * int: the product overflows, and an overflowed offset is a number the store would answer, so
     * a page far past the end would come back holding rows. Refused with the other two rather than
     * clamped, and refused here rather than inside the read, so nothing is opened for a request
     * that cannot be served.
     */
    private static int requireReachableOffset(int page, int size) {
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) {
            throw new ValidationException("page " + page + " is beyond any queue of size " + size);
        }
        return (int) offset;
    }

    /**
     * The other half of the queue: the alerts the caller's own payment-status exclusion is
     * keeping off their screen right now.
     *
     * WHY THIS EXISTS. One transfer status carries two meanings that read as opposites on a fraud
     * desk. A payment the customer withdrew and a payment an analyst declined are both DECLINED,
     * so a desk that hides withdrawn payments - which is the sensible default, since there is
     * nothing left to decide on one - also hides every alert it has itself confirmed as fraud.
     * The screen then contradicts itself in one glance: the counter above the list says Cleared 1
     * while the list under it says no alerts, and nothing on the wire told it why. Both desks
     * open in exactly that state.
     *
     * The queue's own rule is deliberately left alone. Hiding by payment status is right, the
     * counters describing the whole queue are right, and neither is going to change to paper over
     * a naming collision. What was missing is the number that reconciles them, and that is what
     * this answers.
     *
     * HOW TO CALL IT: send the same query string the queue was sent, to this path instead. Every
     * filter means what it means there, including {@code excludeTransferStatus}, which still
     * names the statuses being hidden; this route returns those alerts and only those, so the two
     * responses are the two halves of one filter and their totals add up to the filter without
     * the exclusion. A caller that excluded nothing is hiding nothing and gets an empty page
     * without the store being touched.
     */
    @GetMapping("/alerts/hidden")
    public PageDto<AlertQueueItemDto> listHiddenAlerts(
            @RequestParam(name = "state",       required = false) String state,
            @RequestParam(name = "minAmount",   required = false) BigDecimal minAmount,
            @RequestParam(name = "maxAmount",   required = false) BigDecimal maxAmount,
            @RequestParam(name = "createdFrom", required = false) String createdFrom,
            @RequestParam(name = "createdTo",   required = false) String createdTo,
            @RequestParam(name = "assignee",    required = false) String assignee,
            @RequestParam(name = "excludeTransferStatus", required = false)
            List<String> excludeTransferStatus,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        requireRole(UserRole.FRAUD_ANALYST);
        requireUsableAmountRange(minAmount, maxAmount);

        Set<TransferStatus> hidden = parseTransferStatuses(excludeTransferStatus);

        int pageIndex = requirePage(page);
        int pageSize = requireSize(size);
        int offset = requireReachableOffset(pageIndex, pageSize);

        if (hidden.isEmpty()) {
            return new PageDto<>(List.of(), pageIndex, pageSize, 0);
        }

        // The queue's filter with its payment-status dimension turned inside out: excluding every
        // status the caller did NOT exclude keeps exactly the rows their exclusion removed. It is
        // expressed this way, rather than by adding an inclusion to QueueFilter, because the same
        // predicate then serves both halves and the two cannot drift apart into answering about
        // different sets.
        FraudAlertRepository.QueueFilter filter = new FraudAlertRepository.QueueFilter(
                parseState(state),
                parseInstant(createdFrom),
                parseInstant(createdTo),
                assignee,
                minAmount,
                maxAmount,
                EnumSet.complementOf(EnumSet.copyOf(hidden)));

        try (UowScope scope = new UowScope(uowFactory.begin())) {
            List<AlertQueueItemDto> items = alerts.queuePage(filter, offset, pageSize).stream()
                    .map(FraudController::mapQueueItem)
                    .toList();

            return new PageDto<>(items, pageIndex, pageSize, alerts.queueTotal(filter));
        }
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

        // Six lookups that would otherwise be six connections: the alert, its transfer, that
        // transfer's account, the customer who holds that account, every account that customer
        // holds, and one page of the payments sent from them. Same reasoning as the queue - one
        // unit of work rather than six, because there is no connection pool behind any of them.
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
            List<HistoryItemDto> history =
                    mapHistoryForCustomer(source.id(), 0, DETAIL_HISTORY_ROWS, 0).items();

            return new AlertDetailDto(alertDto, transferDto, history);
        }
    }

    /**
     * How many history rows travel inside the alert detail.
     *
     * Ten, which is what this response has always carried and what the panel heading on both
     * desks promises. It is left at ten rather than raised to {@link PageDto#DEFAULT_SIZE},
     * because the detail cannot say how many rows it left behind: its history is a bare list, so
     * a screen reading it alone can never tell a customer with ten payments from one with two
     * hundred. The route below is the one that answers that, and the one a panel with a
     * "Showing 10 of 137" line under it should be reading.
     */
    private static final int DETAIL_HISTORY_ROWS = 10;

    /**
     * The same customer history, paged, counted, and asked for on its own.
     *
     * A route beside the detail rather than three more numbers inside it. The detail is one
     * screenful of an alert and the history is a table underneath it that an analyst scrolls
     * independently, so the two have different lifetimes: paging the table should not re-read the
     * alert, the payment, the account and the customer behind it. It also keeps the shape the
     * rest of this API pages with, {@link PageDto}, rather than inventing a fourth spelling of
     * page, size and total for one panel.
     *
     * The scope, the order and the reach are {@link #mapHistoryForCustomer}'s and are stated
     * there. Nothing here takes a customer or an account id: the customer is reached through the
     * alert, so this route is exactly as narrow as the detail beside it.
     */
    @GetMapping("/alerts/{id}/history")
    public PageDto<HistoryItemDto> getAlertHistory(
            @PathVariable("id") int id,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        requireRole(UserRole.FRAUD_ANALYST);

        // Refused before a connection is opened for it, like the queue's own paging parameters.
        int pageIndex = requirePage(page);
        int pageSize = requireSize(size);
        int offset = requireReachableOffset(pageIndex, pageSize);

        try (UowScope scope = new UowScope(uowFactory.begin())) {
            FraudAlert alert = alerts.byId(id)
                    .orElseThrow(() -> new NotFoundException("Fraud alert not found: " + id));

            // Both references came from stored rows, not from the request, exactly as in getAlert.
            Transfer transfer = transfers.byId(alert.transferId())
                    .orElseThrow(() -> new DataIntegrityException(
                            "Fraud alert " + id + " points at missing transfer " + alert.transferId()));

            Account source = accounts.byId(transfer.sourceAccountId())
                    .orElseThrow(() -> new DataIntegrityException(
                            "Transfer " + transfer.id() + " points at missing account "
                                    + transfer.sourceAccountId()));

            return mapHistoryForCustomer(source.id(), pageIndex, pageSize, offset);
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
        // An assignee is a different fact anyway - who should look at an alert, where decided_by
        // is who did - and it has a route of its own below. requireRole above has already proved
        // there is a signed-in user.
        String analyst = AuthHelpers.requireUser().username();

        fraudService.decideAndUpdateAlert(
                id,
                req.decision(),
                req.reason(),
                req.notes(),
                analyst
        );

        return getAlert(id);
    }

    // -------------------------------------------------------------------------
    // Assignment
    // -------------------------------------------------------------------------

    /**
     * Takes this alert into the signed-in analyst's name, and answers with the alert as it now
     * stands so the screen needs no second call.
     *
     * No request body, and that is the design rather than an economy. The queue offers two
     * operations, take it and give it back, and the assignee of the first is always the person
     * asking: an analyst assigning work to a colleague is a different feature, with a directory
     * of analysts behind it that this application does not have. Taking the name from the session
     * is also what keeps the audit trail honest, exactly as {@link #decide} takes decided_by from
     * the session and not from the payload.
     *
     * Until this route existed the assignee filter on both desks could never match anything:
     * the column was on the wire, printed on both desks and filtered on, and nothing anywhere
     * could write it.
     */
    @PostMapping("/alerts/{id}/assignment")
    public AlertDetailDto takeAlert(@PathVariable("id") int id) {
        requireRole(UserRole.FRAUD_ANALYST);

        fraudService.assign(id, AuthHelpers.requireUser().username());

        return getAlert(id);
    }

    /**
     * Gives this alert back to the queue.
     *
     * DELETE on the same path rather than a POST carrying an empty assignee, because the two
     * spellings of "no assignee" are exactly what the decision route could not tell apart: there,
     * a blank meant leave it alone and there was no way left to say release it.
     *
     * Any analyst may release any alert, for the reason any analyst may take one: an alert held
     * by somebody who has gone home must not be able to hold up the queue.
     */
    @DeleteMapping("/alerts/{id}/assignment")
    public AlertDetailDto releaseAlert(@PathVariable("id") int id) {
        requireRole(UserRole.FRAUD_ANALYST);

        fraudService.assign(id, null);

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

        // What it was charged if it has settled, and only otherwise a quote from the
        // current policy. Recomputing this made the fraud desk restate what a customer was
        // charged last month whenever the FeePolicy bean was swapped.
        MoneyDto fee = MoneyDto.of(t.feeFor(feePolicy));

        // Unguarded: a transfer always carries its creation instant. The authorization method
        // beside it genuinely may be absent, which is why only one of these two is a ternary.
        String createdAtStr = t.createdAt().toString();
        String authMethod = (t.authMethod() != null ? t.authMethod().method() : null);

        return new TransferInfoDto(
                t.id(),
                "TR-%d".formatted(t.id()),
                t.status().name(),
                fromIban,
                MoneyDto.of(source.balance()),
                t.targetIbanSnapshot(),
                HistoryItemDto.isToIbanInBank(t, this::holdsIban),
                MoneyDto.of(t.amount()),
                fee,
                createdAtStr,
                authMethod
        );
    }

    /**
     * Recent outgoing payments of the CUSTOMER behind the alert: every account they hold, newest
     * first, one page at a time.
     *
     * THE SCOPE IS THE PERSON, NOT THE ACCOUNT, and that is the whole of this change. The question
     * this list answers beside an alert is whether the payment is out of character, and character
     * belongs to a customer. Keyed on the one account the alerted payment left, it hid the very
     * move the desk exists to catch: an amount split across the payer's own accounts so that each
     * part stays under a threshold, which the domain already models in SplitPaymentAlertTest.
     *
     * WHERE THE CUSTOMER COMES FROM decides how far this reaches, so it comes from one place only:
     * the account loaded above from the alerted transfer. Nothing here takes a customer id, so the
     * table is reachable only by opening an alert and only for that alert's customer, and no
     * search across customers appears on this screen.
     *
     * The order and the limit are the query's now. It answers created_at descending with id
     * descending behind it, which is totally ordered where the Comparator this method used to hold
     * left same-instant payments undecided.
     *
     * THE TOTAL IS COUNTED IN THE STORE, over the same predicate the page is taken with, so the
     * two can never describe different sets. It is what the hard ten this method used to end at
     * could not give a screen: a table cut off at ten rows with no way to say how many there were
     * reads as the whole of a customer's history, and it was most often not.
     *
     * @param page   the page index being served, echoed back in the response
     * @param size   how many rows it holds
     * @param offset how many to skip to reach it, already checked against overflow by the caller
     */
    private PageDto<HistoryItemDto> mapHistoryForCustomer(int alertedAccountId,
                                                          int page,
                                                          int size,
                                                          int offset) {
        int customerId = customers.byAccountId(alertedAccountId)
                .orElseThrow(() -> new DataIntegrityException(
                        "Account " + alertedAccountId + " belongs to no customer"))
                .id();

        List<Account> owned = accounts.byCustomerId(customerId);

        Map<Integer, String> ibans = new HashMap<>();
        List<Integer> ids = new ArrayList<>();
        for (Account a : owned) {
            ids.add(a.id());
            ibans.put(a.id(), a.iban().value());
        }

        // One answer per distinct counterparty for the whole page. Only a payment that has not
        // settled reaches the store at all, and the accounts a customer pays repeat, so this is
        // what keeps ten rows from becoming ten lookups on the connection this read already shares.
        Map<String, Boolean> holds = new HashMap<>();
        Predicate<String> inBankNow = iban -> holds.computeIfAbsent(iban, this::holdsIban);

        // An empty status set is the repository's word for every status, which is what a history
        // means. See TransferRepository.bySourceAccountsNewestFirst.
        List<HistoryItemDto> items = transfers.bySourceAccountsNewestFirst(ids, Set.of(), offset, size)
                .stream()
                .map(t -> new HistoryItemDto(
                        t.id(),
                        t.createdAt().toString(),
                        MoneyDto.of(t.amount()),
                        // feeFor and not fee(): what was taken where the payment settled, and what
                        // this tariff would take where it has not. Held and waiting payments fill
                        // most of this list, and fee() alone left every one of their rows without
                        // a fee line at all. See HistoryItemDto, which states what that costs.
                        MoneyDto.of(t.feeFor(feePolicy)),
                        t.status().name(),
                        ibans.get(t.sourceAccountId()),
                        t.targetIbanSnapshot(),
                        HistoryItemDto.isToIbanInBank(t, inBankNow),
                        t.declineReason()
                ))
                .toList();

        return new PageDto<>(items, page, size, transfers.countBySourceAccounts(ids, Set.of()));
    }

    /**
     * Whether this bank holds the account behind an IBAN, in the form
     * {@link HistoryItemDto#isToIbanInBank} asks for it.
     *
     * Wraps the one definition of the in-bank question rather than restating it: see
     * AccountRepository.inBankByIban, which the settle path itself goes through.
     */
    private boolean holdsIban(String iban) {
        return accounts.inBankByIban(iban).isPresent();
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
