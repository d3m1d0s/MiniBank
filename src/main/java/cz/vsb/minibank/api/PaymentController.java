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
import cz.vsb.minibank.domain.value.Money;

import java.util.List;

import static cz.vsb.minibank.api.AuthHelpers.requireCustomerId;

/**
 * REST controller for customer accounts and payment operations.
 */
@CrossOrigin(origins = "http://localhost:5173")
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

        Transfer t = transfers.byId(transferId)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));
        Account acc = accounts.byId(t.sourceAccountId())
                .orElseThrow(() -> new RuntimeException("Account not found"));

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
