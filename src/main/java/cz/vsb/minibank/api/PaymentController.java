package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AccountSummaryDto;
import cz.vsb.minibank.api.dto.BeneficiaryDto;
import cz.vsb.minibank.api.dto.MoneyDto;
import cz.vsb.minibank.api.dto.NewPaymentRequest;
import cz.vsb.minibank.api.dto.NewPaymentResultDto;
import cz.vsb.minibank.application.OwnershipGuard;
import cz.vsb.minibank.application.PaymentOutcome;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.exceptions.ValidationException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
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
     * Resolves the customer whose address book {@link #listMyBeneficiaries} answers with.
     *
     * The guard rather than the customer repository directly, so that "who is this caller" is one
     * decision taken in one place: it is the same call the money-moving paths make, and a second
     * route to the same aggregate is a second thing to remember when that decision changes.
     */
    private final OwnershipGuard ownershipGuard;

    /**
     * The transfer repository and the fee policy are gone from here, and their absence is the
     * point rather than tidiness: this controller used to take both so it could read a payment
     * back after the service had committed and closed its unit of work. It no longer reads
     * anything on that path, so it no longer needs anything to read with. The only repository
     * left is the one the account list actually queries.
     */
    public PaymentController(TransferApplicationService transferService,
                             AccountRepository accounts,
                             OwnershipGuard ownershipGuard) {
        this.transferService = transferService;
        this.accounts = accounts;
        this.ownershipGuard = ownershipGuard;
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
     * The saved payees of the currently authenticated customer, by name.
     *
     * Reads its subject from the session like the account list above, so there is no id for a
     * caller to substitute and nothing here to guard beyond resolving who they are.
     *
     * WHAT IS NOT SENT. {@link Beneficiary} carries a trusted flag, and it decides whether a
     * payment to that payee goes for review. {@link BeneficiaryDto} states the case in full: a
     * customer who can read the flag has been told which payee to pick in order to walk past the
     * fraud check. The order is part of the same decision - by name, case insensitively, because
     * putting trusted payees first would publish the flag through the sequence without ever
     * naming it, and one order settled here also means both platforms show the same list.
     *
     * Not paged, and the three lists item 13 bounds do not include it: an address book is a
     * handful of rows on a form, not a queue, and one lookup answers all of it.
     */
    @GetMapping("/me/beneficiaries")
    public List<BeneficiaryDto> listMyBeneficiaries() {
        return ownershipGuard.requireCaller(requireCustomerId()).beneficiaries().stream()
                .sorted(Comparator.comparing(Beneficiary::name, String.CASE_INSENSITIVE_ORDER))
                .map(b -> new BeneficiaryDto(b.id(), b.name(), b.iban().value()))
                .toList();
    }

    /**
     * Creates a new payment and returns information about status, charged amount and fee.
     *
     * TWO DESTINATIONS, ONE ROUTE EACH. A saved payee goes to the service method that has always
     * been able to take one and until now had no way in from HTTP, which is what left
     * {@code beneficiaryTrusted} permanently false for every payment the browser made and the
     * trusted branch of the risk rules dead outside the console. A typed IBAN goes where it
     * always went, unchanged.
     */
    @PostMapping("/payments")
    public ResponseEntity<NewPaymentResultDto> createPayment(@RequestBody NewPaymentRequest req) {

        int customerId = requireCustomerId();

        boolean namesBeneficiary = req.beneficiaryId() != null;
        boolean namesIban = req.targetIban() != null && !req.targetIban().isBlank();

        // Refused rather than resolved by preferring one of them. A request naming a saved payee
        // and an account number is a caller that has lost track of which it meant, and the two
        // can perfectly well be different people; picking one moves money to whichever the guess
        // landed on. Naming neither is left to submitPaymentToIban below, which refuses a missing
        // destination with the same message it always has.
        if (namesBeneficiary && namesIban) {
            throw new ValidationException(
                    "A payment names either a saved beneficiary or a target IBAN, not both");
        }

        // Everything below is read off what the service did, inside the transaction that did it.
        // This used to re-read the transfer and the account after that transaction had closed,
        // so the balance shown could be one another transaction had left behind.
        PaymentOutcome outcome = namesBeneficiary
                ? transferService.submitPaymentByBeneficiary(
                        customerId,
                        req.sourceAccountId(),
                        req.beneficiaryId(),
                        req.amountCzk(),
                        req.message())
                : transferService.submitPaymentToIban(
                        customerId,
                        req.sourceAccountId(),
                        req.targetIban(),
                        req.amountCzk(),
                        req.message());

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
