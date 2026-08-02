package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AccountSummaryDto;
import cz.vsb.minibank.api.dto.NewPaymentRequest;
import cz.vsb.minibank.api.dto.NewPaymentResultDto;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cz.vsb.minibank.domain.FeePolicy;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.value.Money;

import java.util.List;

import static cz.vsb.minibank.api.AuthHelpers.requireCustomerId;

/**
 * REST controller for customer accounts and payment operations.
 */
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
@RestController
@RequestMapping("/api")
public class PaymentController {

    private final TransferApplicationService transferService;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final FeePolicy feePolicy;

    public PaymentController(TransferApplicationService transferService,
                             AccountRepository accounts,
                             TransferRepository transfers,
                             FeePolicy feePolicy) {
        this.transferService = transferService;
        this.accounts = accounts;
        this.transfers = transfers;
        this.feePolicy = feePolicy;
    }

    /**
     * Lists accounts for the currently authenticated customer.
     *
     * There is no route that takes a customer id. This one reads its subject from the
     * session, so a caller has no way to name somebody else and no ownership check is
     * needed. The twin that took the id in the path was removed rather than guarded:
     * two doors onto the same data are two standing obligations to remember the check.
     */
    @GetMapping("/me/accounts")
    public List<AccountSummaryDto> listMyAccounts() {
        return accounts.byCustomerId(requireCustomerId()).stream()
                .map(this::toAccountSummary)
                .toList();
    }

    /**
     * Creates a new payment and returns information about status, charged amount and fee.
     */
    @PostMapping("/payments")
    public ResponseEntity<NewPaymentResultDto> createPayment(@RequestBody NewPaymentRequest req) {

        int customerId = requireCustomerId();

        int transferId = transferService.submitPaymentToIban(
                customerId,
                req.sourceAccountId(),
                req.targetIban(),
                req.amountCzk(),
                req.message()
        );

        // transferId is the service's own return value for a row it just committed, so
        // neither of these can be the caller naming something that does not exist.
        Transfer t = transfers.byId(transferId)
                .orElseThrow(() -> new DataIntegrityException(
                        "Transfer " + transferId + " disappeared after creation"));
        Account acc = accounts.byId(t.sourceAccountId())
                .orElseThrow(() -> new DataIntegrityException(
                        "Transfer " + transferId + " points at missing account " + t.sourceAccountId()));

        // True for a held transfer as well: nothing has been debited and a confirmation step is
        // still to come. Reading it off WAITING_AUTH alone made the creation screen announce a
        // held payment as completed, with a "New balance" that had not changed and
        // "Authorization required: NO".
        boolean authorizationRequired = (t.status() == TransferStatus.WAITING_AUTH
                || t.status() == TransferStatus.HELD_FOR_REVIEW);

        // A14: the stored fee once the payment has settled, a quote from the current policy
        // while it has not. This screen shows the fee next to the new balance, and recomputing
        // it is what let the two disagree the moment the FeePolicy bean changed.
        Money fee = t.feeFor(feePolicy);
        Money charged = t.amount().plus(fee);

        NewPaymentResultDto dto = new NewPaymentResultDto(
                t.id(),
                t.status().name(),
                charged.toString(),
                fee.toString(),
                acc.balance().toString(),
                authorizationRequired
        );

        return ResponseEntity.ok(dto);
    }

    /**
     * Maps an account entity to its API summary representation.
     */
    private AccountSummaryDto toAccountSummary(Account a) {
        return new AccountSummaryDto(
                a.id(),
                a.iban().value(),
                a.balance().toString()
        );
    }
}
