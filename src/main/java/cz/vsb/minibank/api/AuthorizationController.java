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

import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

import static cz.vsb.minibank.api.AuthHelpers.requireCustomerId;

/**
 * REST controller for transfer authorization and cancellation use cases.
 */
@CrossOrigin(origins = "http://localhost:5173")
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
     */
    @GetMapping("/transfers/{id}")
    public TransferDetailsDto transferDetails(@PathVariable("id") int id) {
        Transfer t = transfers.byId(id)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));
        Account acc = accounts.byId(t.sourceAccountId())
                .orElseThrow(() -> new RuntimeException("Account not found"));

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
        transferService.authorizePayment(id, req.otp());

        Transfer t = transfers.byId(id)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));
        Account acc = accounts.byId(t.sourceAccountId())
                .orElseThrow(() -> new RuntimeException("Account not found"));

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
        transferService.cancelPayment(id);

        Transfer t = transfers.byId(id)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));
        Account acc = accounts.byId(t.sourceAccountId())
                .orElseThrow(() -> new RuntimeException("Account not found"));

        return new AuthorizePaymentResult(
                t.id(),
                t.status().name(),
                null,
                acc.balance().toString(),
                t.declineReason()
        );
    }
}
