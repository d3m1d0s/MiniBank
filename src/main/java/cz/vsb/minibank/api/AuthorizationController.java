package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AuthorizePaymentRequest;
import cz.vsb.minibank.api.dto.AuthorizePaymentResult;
import cz.vsb.minibank.api.dto.TransferDetailsDto;
import cz.vsb.minibank.api.dto.WaitingTransferItemDto;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
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
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
@RestController
@RequestMapping("/api")
public class AuthorizationController {

    private final TransferApplicationService transferService;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final FeePolicy feePolicy;

    public AuthorizationController(TransferApplicationService transferService,
                                   AccountRepository accounts,
                                   TransferRepository transfers,
                                   FeePolicy feePolicy) {
        this.transferService = transferService;
        this.accounts = accounts;
        this.transfers = transfers;
        this.feePolicy = feePolicy;
    }

    /**
     * Lists all transfers in WAITING_AUTH state for the specified customer.
     *
     * No role check and no ownership check: any authenticated caller can read any
     * customer's waiting transfers here. The guarded twin is {@link #listMyWaiting()},
     * which is guarded only because it needs a customer id, not as an access rule.
     * Closing this is backlog item A4, and the type to throw is NotFoundException.
     *
     * A3 guarded the write paths and left this one open, so it is currently the oracle those
     * 404s were meant to remove: this route names a stranger's transfer ids for free. Use
     * BootstrapServices.ownershipGuard so the two answer identically.
     */
    @GetMapping("/customers/{customerId}/waiting-transfers")
    public List<WaitingTransferItemDto> listWaiting(@PathVariable("customerId") int customerId) {
        List<WaitingTransferItemDto> result = new ArrayList<>();

        for (Account acc : accounts.byCustomerId(customerId)) {
            for (Transfer t : transfers.bySourceAccount(acc.id())) {
                if (t.status() == TransferStatus.WAITING_AUTH) {
                    result.add(new WaitingTransferItemDto(
                            t.id(),
                            t.targetIbanSnapshot(),
                            t.amount().toString(),
                            t.createdAt().toString(),
                            t.authMethod() != null
                                    ? t.authMethod().toString()
                                    : ""
                    ));
                }
            }
        }
        return result;
    }

    /**
     * Shortcut endpoint that lists waiting transfers for the current authenticated customer.
     */
    @GetMapping("/me/waiting-transfers")
    public List<WaitingTransferItemDto> listMyWaiting() {
        int customerId = requireCustomerId();
        return listWaiting(customerId);
    }

    /**
     * Returns detailed information for a transfer including fee, status and authorization metadata.
     *
     * No ownership check: any authenticated caller can read any transfer, including the
     * source account's IBAN and balance. Closing this is backlog item A4, and a transfer
     * belonging to somebody else must throw NotFoundException so it is indistinguishable
     * from an id that does not exist - the same type and the same message
     * TransferApplicationService.requireTransfer already throws, so a read and a write can
     * never disagree about whether a transfer exists for you.
     */
    @GetMapping("/transfers/{id}")
    public TransferDetailsDto transferDetails(@PathVariable("id") int id) {
        Transfer t = transfers.byId(id)
                .orElseThrow(() -> new NotFoundException("Transfer not found: " + id));
        // The id came from the store, not from the caller, so a missing account is our fault.
        Account acc = accounts.byId(t.sourceAccountId())
                .orElseThrow(() -> new DataIntegrityException(
                        "Transfer " + id + " points at missing account " + t.sourceAccountId()));

        var fee = t.feeAmount(feePolicy);

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
                t.authMethod() != null
                        ? t.authMethod().toString()
                        : "",
                triesLeft,
                authValidUntilStr
        );
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
            var fee = t.feeAmount(feePolicy);
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
