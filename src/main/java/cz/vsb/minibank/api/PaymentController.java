package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AccountSummaryDto;
import cz.vsb.minibank.api.dto.MoneyDto;
import cz.vsb.minibank.api.dto.NewPaymentRequest;
import cz.vsb.minibank.api.dto.NewPaymentResultDto;
import cz.vsb.minibank.application.PaymentOutcome;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.repository.AccountRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cz.vsb.minibank.api.AuthHelpers.requireCustomerId;

/**
 * REST controller for customer accounts and payment operations.
 */
@RestController
@RequestMapping("/api")
public class PaymentController {

    private final TransferApplicationService transferService;
    private final AccountRepository accounts;

    /**
     * The transfer repository and the fee policy are gone from here, and their absence is the
     * point rather than tidiness: this controller used to take both so it could read a payment
     * back after the service had committed and closed its unit of work. It no longer reads
     * anything on that path, so it no longer needs anything to read with. The only repository
     * left is the one the account list actually queries.
     */
    public PaymentController(TransferApplicationService transferService,
                             AccountRepository accounts) {
        this.transferService = transferService;
        this.accounts = accounts;
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

        // Everything below is read off what the service did, inside the transaction that did it.
        // This used to re-read the transfer and the account after that transaction had closed,
        // so the balance shown could be one another transaction had left behind.
        PaymentOutcome outcome = transferService.submitPaymentToIban(
                customerId,
                req.sourceAccountId(),
                req.targetIban(),
                req.amountCzk(),
                req.message()
        );

        // True for a held transfer as well: nothing has been debited and a confirmation step is
        // still to come. Reading it off WAITING_AUTH alone made the creation screen announce a
        // held payment as completed, with a "New balance" that had not changed and
        // "Authorization required: NO".
        boolean authorizationRequired = (outcome.status() == TransferStatus.WAITING_AUTH
                || outcome.status() == TransferStatus.HELD_FOR_REVIEW);

        NewPaymentResultDto dto = new NewPaymentResultDto(
                outcome.transferId(),
                outcome.status().name(),
                MoneyDto.of(outcome.amount().plus(outcome.fee())),
                MoneyDto.of(outcome.fee()),
                MoneyDto.of(outcome.balance()),
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
                MoneyDto.of(a.balance())
        );
    }
}
