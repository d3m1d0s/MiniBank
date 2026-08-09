package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AuthorizePaymentRequest;
import cz.vsb.minibank.api.dto.AuthorizePaymentResult;
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

import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

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
     * Opens the unit of work the two read endpoints run inside.
     *
     * Not used by authorize or cancel: those go through the application service, which opens its
     * own, and their re-reads afterwards are a separate problem - they run after that
     * transaction has committed and can therefore see another one's balance.
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
     * Lists the current customer's transfers that have not settled and have not been stopped:
     * the ones waiting for their code, and the ones the bank is still reviewing.
     *
     * There is no route that takes a customer id. This one reads its subject from the
     * session, so a caller has no way to name somebody else. The twin that took the id in
     * the path was removed rather than guarded: it handed out a stranger's transfer ids for
     * free, which is exactly the disclosure the 404s elsewhere exist to prevent.
     */
    @GetMapping("/me/waiting-transfers")
    public List<WaitingTransferItemDto> listMyWaiting() {
        int customerId = requireCustomerId();

        // The same N+1 the alert queue had, on a screen that is polled far more often: one
        // lookup for the customer's accounts, then one per account for its transfers. Without a
        // unit of work each of those opens and tears down its own JDBC connection, because this
        // project has no connection pool.
        try (UowScope scope = new UowScope(uowFactory.begin())) {
            return waitingFor(customerId);
        }
    }

    /**
     * Called only from {@link #listMyWaiting}, inside its unit of work.
     */
    private List<WaitingTransferItemDto> waitingFor(int customerId) {
        List<WaitingTransferItemDto> result = new ArrayList<>();

        for (Account acc : accounts.byCustomerId(customerId)) {
            for (Transfer t : transfers.bySourceAccount(acc.id())) {
                // Held transfers belong in this list. Filtering them out would make a
                // customer's payment disappear from the only screen that mentions it, with
                // nothing on any screen to say where it went.
                if (t.status() == TransferStatus.WAITING_AUTH
                        || t.status() == TransferStatus.HELD_FOR_REVIEW) {
                    result.add(new WaitingTransferItemDto(
                            t.id(),
                            t.targetIbanSnapshot(),
                            t.amount().toString(),
                            t.createdAt().toString(),
                            authMethodOf(t),
                            t.status().name()
                    ));
                }
            }
        }
        return result;
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
        // policy. A14: recomputing this on every read made a settled payment's fee a function
        // of whichever FeePolicy bean is wired today.
        var fee = t.feeFor(feePolicy);

        int maxAttempts = TransferApplicationService.MAX_OTP_ATTEMPTS;
        int triesLeft = Math.max(0, maxAttempts - t.authAttempts());

        String authValidUntilStr = null;
        if (t.authValidUntil() != null) {
            authValidUntilStr = t.authValidUntil().toString();
        }

        return new TransferDetailsDto(
                t.id(),
                acc.iban().value(),
                acc.balance().toString(),
                t.targetIbanSnapshot(),
                t.amount().toString(),
                fee.toString(),
                t.status().name(),
                t.createdAt().toString(),
                t.settledAt() != null ? t.settledAt().toString() : null,
                t.message(),
                authMethodOf(t),
                triesLeft,
                authValidUntilStr
        );
    }

    /**
     * The name of the method that authorized a transfer, or an empty string when it has none.
     *
     * {@code Payment} is an abstract base class with no {@code toString}, so asking it for one
     * yields {@code Object}'s identity string: both of these screens used to show the customer
     * something of the shape {@code cz.vsb.minibank.domain.CardPayment@13e69db6}. The field it
     * should read is {@code method()}, which is what the two persistence mappers and the fraud
     * desk already store and return.
     */
    private static String authMethodOf(Transfer t) {
        return t.authMethod() != null ? t.authMethod().method() : "";
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

        transferService.authorizePayment(customerId, id, req.otp());

        // The service resolved this id inside a committed unit of work, so a failure here
        // means the store lost a row, not that the caller named a transfer that never existed.
        Transfer t = transfers.byId(id)
                .orElseThrow(() -> new DataIntegrityException(
                        "Transfer " + id + " disappeared after authorization"));
        Account acc = accounts.byId(t.sourceAccountId())
                .orElseThrow(() -> new DataIntegrityException(
                        "Transfer " + id + " points at missing account " + t.sourceAccountId()));

        String chargedAmount = null;
        if (t.status() == TransferStatus.SENT) {
            // The stored fee, which on this branch always exists: a SENT transfer went through
            // Transfer.send, which writes it. A14 - what the customer is told they were charged
            // must be what they were charged, not what today's policy would charge.
            var fee = t.feeFor(feePolicy);
            var total = t.amount().plus(fee);
            chargedAmount = total.toString();
        }

        return new AuthorizePaymentResult(
                t.id(),
                t.status().name(),
                chargedAmount,
                acc.balance().toString(),
                t.declineReason()
        );
    }

    /**
     * UC 19 - Cancels a payment order initiated by the customer.
     */
    @PostMapping("/transfers/{id}/cancel")
    public AuthorizePaymentResult cancel(@PathVariable("id") int id) {
        int customerId = requireCustomerId();
        transferService.cancelPayment(customerId, id);

        // The re-reads below stay unscoped on purpose: the service has already proved this
        // transfer is the caller's before either of them runs.
        Transfer t = transfers.byId(id)
                .orElseThrow(() -> new DataIntegrityException(
                        "Transfer " + id + " disappeared after cancellation"));
        // cancelPayment never loads the account, so this is the first thing to touch it.
        Account acc = accounts.byId(t.sourceAccountId())
                .orElseThrow(() -> new DataIntegrityException(
                        "Transfer " + id + " points at missing account " + t.sourceAccountId()));

        return new AuthorizePaymentResult(
                t.id(),
                t.status().name(),
                null,
                acc.balance().toString(),
                t.declineReason()
        );
    }
}
