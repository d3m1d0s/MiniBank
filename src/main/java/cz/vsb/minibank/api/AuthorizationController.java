package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AuthorizePaymentRequest;
import cz.vsb.minibank.api.dto.AuthorizePaymentResult;
import cz.vsb.minibank.api.dto.HistoryItemDto;
import cz.vsb.minibank.api.dto.MoneyDto;
import cz.vsb.minibank.api.dto.PageDto;
import cz.vsb.minibank.api.dto.TransferDetailsDto;
import cz.vsb.minibank.api.dto.WaitingTransferItemDto;
import cz.vsb.minibank.application.OwnershipGuard;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import cz.vsb.minibank.domain.FeePolicy;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.NotFoundException;
import cz.vsb.minibank.domain.exceptions.ValidationException;
import cz.vsb.minibank.application.PaymentOutcome;

import org.springframework.web.bind.annotation.*;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static cz.vsb.minibank.api.AuthHelpers.requireCustomerId;

/**
 * REST controller for transfer authorization and cancellation use cases.
 */
@RestController
@RequestMapping("/api")
public class AuthorizationController {

    private final TransferApplicationService transferService;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final FeePolicy feePolicy;
    private final OwnershipGuard ownershipGuard;

    /**
     * Opens the unit of work the three read endpoints run inside.
     *
     * Not used by authorize or cancel, and they no longer need it: the application service opens
     * its own and now hands back a {@link PaymentOutcome} read off the aggregates inside it. Both
     * used to re-read the transfer and the account afterwards, from outside that transaction,
     * which is how a customer could be shown a balance another transaction had left.
     */
    private final UnitOfWorkFactory uowFactory;

    public AuthorizationController(TransferApplicationService transferService,
                                   AccountRepository accounts,
                                   TransferRepository transfers,
                                   FeePolicy feePolicy,
                                   OwnershipGuard ownershipGuard,
                                   UnitOfWorkFactory uowFactory) {
        this.transferService = transferService;
        this.accounts = accounts;
        this.transfers = transfers;
        this.feePolicy = feePolicy;
        this.ownershipGuard = ownershipGuard;
        this.uowFactory = uowFactory;
    }

    /**
     * The two statuses the waiting screen is about: a payment waiting for the customer's code,
     * and one the bank is still reviewing.
     *
     * Held transfers belong in this list. Filtering them out would make a customer's payment
     * disappear from the only screen that mentions it, with nothing on any screen to say where
     * it went. The set is passed into the query rather than applied to its answer, because a
     * filter applied after a page has been cut would answer three rows and call them a page of
     * twenty-five.
     */
    private static final Set<TransferStatus> STILL_OPEN =
            EnumSet.of(TransferStatus.WAITING_AUTH, TransferStatus.HELD_FOR_REVIEW);

    /**
     * Every status, which is what a customer's own history shows.
     *
     * Empty means all, and that is the repository's contract rather than an oversight here: a
     * history that listed the statuses it wanted would quietly stop showing one the day a status
     * was added, and a payment vanishing from the only complete list of them is the defect this
     * screen exists to close.
     */
    private static final Set<TransferStatus> EVERY_STATUS = Set.of();

    /**
     * Lists the current customer's transfers that have not settled and have not been stopped:
     * the ones waiting for their code, and the ones the bank is still reviewing.
     *
     * There is no route that takes a customer id. This one reads its subject from the
     * session, so a caller has no way to name somebody else. The twin that took the id in
     * the path was removed rather than guarded: it handed out a stranger's transfer ids for
     * free, which is exactly the disclosure the 404s elsewhere exist to prevent.
     */
    @GetMapping("/me/waiting-transfers")
    public PageDto<WaitingTransferItemDto> listMyWaiting(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {

        int customerId = requireCustomerId();

        // Validated before a connection is opened for it, following the alert queue's filters: a
        // page nobody can serve is a mistake in the request, not an empty answer.
        int wantedPage = requirePage(page);
        int wantedSize = requireSize(size);

        // One lookup for the caller and two statements for the page, where this used to ask for
        // the customer's accounts and then for every transfer of each of them. Without a unit of
        // work each of those calls opens and tears down its own JDBC connection, because this
        // project has no connection pool.
        try (UowScope scope = new UowScope(uowFactory.begin())) {
            return pageOf(customerId, STILL_OPEN, wantedPage, wantedSize,
                    AuthorizationController::toWaitingItem);
        }
    }

    /**
     * The customer's own payment history: every payment from every account they hold, newest
     * first, whatever became of it.
     *
     * The screen this serves is the one a customer had no way to reach. A payment left their
     * world the moment it was sent or declined - the waiting list holds only the two open
     * statuses, and {@link #transferDetails} answers for any of their transfers but only to
     * somebody who already has the id. The analyst could read the last ten payments of the same
     * customer and the customer could not read their own.
     *
     * NO ID COMES FROM THE CALLER, so there is nothing here for requireOwnedTransfer to guard.
     * The caller is resolved from the session and the query is bounded to the account ids on
     * their own customer row, which is where every other ownership decision in this application
     * is taken from - the scope is a property of the query rather than a check applied to its
     * answer, which is the stronger of the two.
     */
    @GetMapping("/me/transfers")
    public PageDto<HistoryItemDto> listMyTransfers(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {

        int customerId = requireCustomerId();
        int wantedPage = requirePage(page);
        int wantedSize = requireSize(size);

        try (UowScope scope = new UowScope(uowFactory.begin())) {
            // The account numbers this customer holds, so a row can say which of them the payment
            // left. The ids come back with them, but the page is still scoped by pageOf through the
            // ownership guard: this lookup prints, it does not authorize.
            Map<Integer, String> ibans = new HashMap<>();
            for (Account a : accounts.byCustomerId(customerId)) {
                ibans.put(a.id(), a.iban().value());
            }

            // Raised once for the page for the same reason as the map above it. Only a payment
            // that has not settled asks the store whether its counterparty is ours, and the
            // accounts one customer pays repeat, so a page of a hundred rows is nowhere near a
            // hundred lookups.
            Map<String, Boolean> holds = new HashMap<>();
            Predicate<String> inBankNow = iban -> holds.computeIfAbsent(iban, this::holdsIban);

            return pageOf(customerId, EVERY_STATUS, wantedPage, wantedSize,
                    t -> toHistoryItem(t, ibans, inBankNow, feePolicy));
        }
    }

    /**
     * One page of the caller's own payments, mapped by whichever of the two lists asked for it.
     *
     * Called only from the two endpoints above, inside the unit of work each of them opened.
     * Shared because the two lists differ in exactly two things - which statuses they admit and
     * what a row looks like on the wire - and everything else about them, the ownership scope,
     * the order, the count beside the list, is one decision that must not come to be taken twice.
     */
    private <T> PageDto<T> pageOf(int customerId,
                                  Set<TransferStatus> statuses,
                                  int page,
                                  int size,
                                  Function<Transfer, T> toItem) {

        // The account ids off the caller's own customer row, which is what OwnershipGuard
        // resolves ownership from everywhere else. One lookup, and the accounts themselves are
        // not needed: these two lists print the payment, not the account behind it.
        List<Integer> accountIds = ownershipGuard.requireCaller(customerId).accountIds();

        int total = transfers.countBySourceAccounts(accountIds, statuses);

        // Clamped rather than refused. A page number far enough out that its first row does not
        // fit in an int is a page past the end of any list, and the honest answer to that is the
        // empty page the store would have given, not an error about arithmetic.
        int offset = (int) Math.min((long) page * size, Integer.MAX_VALUE);

        List<T> items = transfers.bySourceAccountsNewestFirst(accountIds, statuses, offset, size)
                .stream()
                .map(toItem)
                .toList();

        return new PageDto<>(items, page, size, total);
    }

    private static WaitingTransferItemDto toWaitingItem(Transfer t) {
        return new WaitingTransferItemDto(
                t.id(),
                t.targetIbanSnapshot(),
                MoneyDto.of(t.amount()),
                t.createdAt().toString(),
                authMethodOf(t),
                t.status().name()
        );
    }

    /**
     * A history row, in the shape the fraud desk's history already uses.
     *
     * The same record rather than a second one for the same row: this is the analyst's history
     * list read by its owner instead, and the one thing that differs - a waiting payment reads
     * "Waiting for your code" here and "Awaiting customer code" on the desk - is a matter of who
     * is being addressed, settled on the client, which is what the glossary's audience parameter
     * is for.
     *
     * THE ACCOUNT THE PAYMENT LEFT is carried, and a customer holding two of them needs it: this
     * list spans every account they hold, so without it a row cannot say which balance it moved.
     * It is resolved out of a map the caller raised once for the page, rather than by asking the
     * store per row, which is the same reason the count and the page are two statements and not
     * one per transfer. A payment from an account outside that map is not a row this caller may
     * be shown at all, so it is refused rather than printed without its origin.
     */
    private static HistoryItemDto toHistoryItem(Transfer t,
                                                Map<Integer, String> ibans,
                                                Predicate<String> inBankNow,
                                                FeePolicy feePolicy) {
        String fromIban = ibans.get(t.sourceAccountId());
        if (fromIban == null) {
            throw new DataIntegrityException(
                    "Transfer " + t.id() + " was sent from account " + t.sourceAccountId()
                            + ", which is not one of the caller's accounts");
        }

        return new HistoryItemDto(
                t.id(),
                t.createdAt().toString(),
                MoneyDto.of(t.amount()),
                // feeFor and not fee(): the charge where there is one, and this tariff's answer
                // where the payment has not settled, so that every row carries a fee line. The
                // charge still wins wherever one was taken, which is what keeps a settled row off
                // a tariff that changed after it. See HistoryItemDto, which states what that costs.
                MoneyDto.of(t.feeFor(feePolicy)),
                t.status().name(),
                fromIban,
                t.targetIbanSnapshot(),
                HistoryItemDto.isToIbanInBank(t, inBankNow),
                t.declineReason()
        );
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
     * The page index a request asked for, defaulting to the first.
     *
     * Refused rather than clamped when it is negative, unlike the offset arithmetic further down:
     * a negative page is a caller that has computed one wrongly, and answering the first page
     * would hide that from them for as long as the mistake lasted.
     */
    private static int requirePage(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            throw new ValidationException("page must not be negative: " + page);
        }
        return page;
    }

    /**
     * The page size a request asked for, defaulting and bounded by {@link PageDto}.
     *
     * The ceiling is the point of the parameter rather than a formality: a size a caller may set
     * without limit is the unbounded list this endpoint was paged to close, reachable by asking
     * for it politely.
     */
    private static int requireSize(Integer size) {
        if (size == null) {
            return PageDto.DEFAULT_SIZE;
        }
        if (size < 1 || size > PageDto.MAX_SIZE) {
            throw new ValidationException(
                    "size must be between 1 and " + PageDto.MAX_SIZE + ": " + size);
        }
        return size;
    }

    /**
     * Returns detailed information for a transfer including fee, status and authorization metadata.
     *
     * A transfer that belongs to somebody else is refused as not found, with the same type
     * and message {@code TransferApplicationService.requireTransfer} throws, so a read and a
     * write can never disagree about whether a transfer exists for the caller. Reading a
     * stranger's row disclosed the source IBAN and its live balance, which is the
     * reconnaissance the write-side 404s were meant to deny.
     */
    @GetMapping("/transfers/{id}")
    public TransferDetailsDto transferDetails(@PathVariable("id") int id) {
        int customerId = requireCustomerId();

        // Three lookups - the caller, the transfer, its account - and the same rule as the two
        // reads above: a read path in this controller runs inside one unit of work.
        try (UowScope scope = new UowScope(uowFactory.begin())) {
            return detailsOf(customerId, id);
        }
    }

    /**
     * Called only from {@link #transferDetails}, inside its unit of work.
     */
    private TransferDetailsDto detailsOf(int customerId, int id) {
        var caller = ownershipGuard.requireCaller(customerId);
        Transfer t = transfers.byId(id)
                .orElseThrow(() -> new NotFoundException("Transfer not found: " + id));
        ownershipGuard.requireOwnedTransfer(caller, t);

        // The id came from the store, not from the caller, so a missing account is our fault.
        Account acc = accounts.byId(t.sourceAccountId())
                .orElseThrow(() -> new DataIntegrityException(
                        "Transfer " + id + " points at missing account " + t.sourceAccountId()));

        // What it was charged if it has settled, and only otherwise a quote from the current
        // policy. Recomputing this on every read made a settled payment's fee a function
        // of whichever FeePolicy bean is wired today.
        var fee = t.feeFor(feePolicy);

        // Both are answers to "how do I finish authorizing this", so they exist only while the
        // transfer is actually asking for a code. Sent unconditionally, a held transfer reported
        // "Tries left: 3" next to a Confirm button it will not accept, and a CREATED one carried
        // a full allowance for a step it had not reached. Null is the honest reading, and both
        // clients already treat these two as optional.
        Integer triesLeft = null;
        String authValidUntilStr = null;
        if (t.status() == TransferStatus.WAITING_AUTH) {
            triesLeft = Math.max(0, TransferApplicationService.MAX_OTP_ATTEMPTS - t.authAttempts());
            if (t.authValidUntil() != null) {
                authValidUntilStr = t.authValidUntil().toString();
            }
        }

        return new TransferDetailsDto(
                t.id(),
                acc.iban().value(),
                MoneyDto.of(acc.balance()),
                t.targetIbanSnapshot(),
                HistoryItemDto.isToIbanInBank(t, this::holdsIban),
                MoneyDto.of(t.amount()),
                MoneyDto.of(fee),
                t.status().name(),
                t.createdAt().toString(),
                t.settledAt() != null ? t.settledAt().toString() : null,
                dispatchStateOf(t),
                t.message(),
                t.declineReason(),
                authMethodOf(t),
                triesLeft,
                authValidUntilStr
        );
    }

    /**
     * The name of the method that authorized a transfer, or null when it has none.
     *
     * {@code Payment} is an abstract base class with no {@code toString}, so asking it for one
     * yields {@code Object}'s identity string: both of these screens used to show the customer
     * something of the shape {@code cz.vsb.minibank.domain.CardPayment@13e69db6}. The field it
     * should read is {@code method()}, which is what the two persistence mappers and the fraud
     * desk already store and return.
     *
     * NULL AND NOT AN EMPTY STRING, which is the whole of this method's remaining reason to exist.
     * The same field is built in {@code FraudController.mapTransferInfo} and has always answered
     * null there, so one wire field arrived in three shapes: a name, a null and an empty string.
     * A client cannot tell the third from the second without knowing which endpoint it came from,
     * and both front ends read it through a label lookup that answers "" for "" and therefore drew
     * an empty cell where a settled payment has no recorded method. Absent is one fact and it now
     * has one spelling.
     */
    private static String authMethodOf(Transfer t) {
        return t.authMethod() != null ? t.authMethod().method() : null;
    }

    /**
     * What this payment still owes the payment network, or null when it owes it nothing.
     *
     * Sent beside {@code toIbanInBank} rather than instead of it, because the two answer different
     * questions and a client cannot derive either from the other. The boolean says which side of
     * this bank's edge the money was going; this says how far it has got on the way out - PENDING
     * is settled and queued for the network, DISPATCHED is settled and handed over. Null is the
     * common case and covers three situations at once, which {@link DispatchState} sets out: an
     * intra-bank payment, anything that has not settled, and every row written before the column
     * existed. That is why {@link HistoryItemDto#isToIbanInBank} may not read this field as the
     * opposite answer, and why this one is not a substitute for it.
     */
    private static String dispatchStateOf(Transfer t) {
        return t.dispatchState() != null ? t.dispatchState().name() : null;
    }

    /**
     * UC 05 - Authorizes a payment using the provided one time password.
     */
    @PostMapping("/transfers/{id}/authorize")
    public AuthorizePaymentResult authorize(@PathVariable("id") int id,
                                            @RequestBody AuthorizePaymentRequest req) {
        // Settled before the body is read: who the caller is decides whether this transfer
        // exists for them at all. A user with no customer id is refused here with the same
        // answer for every transfer id, including ones that do not exist.
        int customerId = requireCustomerId();

        // Checked here so an omitted field is not silently treated as a wrong code and
        // charged against the three attempts.
        if (req.otp() == null || req.otp().isBlank()) {
            throw new ValidationException("Missing one-time password in the authorize request");
        }

        PaymentOutcome outcome = transferService.authorizePayment(customerId, id, req.otp());

        MoneyDto chargedAmount = null;
        if (outcome.status() == TransferStatus.SENT) {
            // The stored fee, which on this branch always exists: a SENT transfer went through
            // Transfer.send, which writes it. What the customer is told they were charged
            // must be what they were charged, not what today's policy would charge.
            chargedAmount = MoneyDto.of(outcome.amount().plus(outcome.fee()));
        }

        return new AuthorizePaymentResult(
                outcome.transferId(),
                outcome.status().name(),
                chargedAmount,
                MoneyDto.of(outcome.balance()),
                outcome.declineReason()
        );
    }

    /**
     * UC 19 - Cancels a payment order initiated by the customer.
     */
    @PostMapping("/transfers/{id}/cancel")
    public AuthorizePaymentResult cancel(@PathVariable("id") int id) {
        int customerId = requireCustomerId();
        PaymentOutcome outcome = transferService.cancelPayment(customerId, id);

        // Null rather than the fee this transfer would have cost: cancelling debits nothing, and
        // a charge on a withdrawn payment is the one number this screen must not show.
        return new AuthorizePaymentResult(
                outcome.transferId(),
                outcome.status().name(),
                null,
                MoneyDto.of(outcome.balance()),
                outcome.declineReason()
        );
    }
}
