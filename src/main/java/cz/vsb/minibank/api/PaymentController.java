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
     * Lists all accounts for the given customer identifier.
     *
     * No role check and no ownership check: any authenticated caller can read any
     * customer's account ids, IBANs and balances here. The guarded twin is
     * {@link #listMyAccounts()}, which is guarded only because it needs a customer id,
     * not as an access rule. Closing this is backlog item A3, and the type to throw is
     * NotFoundException.
     */
    @GetMapping("/customers/{customerId}/accounts")
    public List<AccountSummaryDto> listAccounts(@PathVariable("customerId") int customerId) {
        return accounts.byCustomerId(customerId).stream()
                .map(this::toAccountSummary)
                .toList();
    }

    /**
     * Lists accounts for the currently authenticated customer.
     */
    @GetMapping("/me/accounts")
    public List<AccountSummaryDto> listMyAccounts() {
        int customerId = requireCustomerId();
        return listAccounts(customerId);
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

        boolean authorizationRequired = (t.status() == TransferStatus.WAITING_AUTH);

        Money fee = t.feeAmount(feePolicy);
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
