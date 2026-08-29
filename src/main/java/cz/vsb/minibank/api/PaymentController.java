package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AccountSummaryDto;
import cz.vsb.minibank.api.dto.BeneficiaryDto;
import cz.vsb.minibank.api.dto.DailyOutflowDto;
import cz.vsb.minibank.api.dto.MeDto;
import cz.vsb.minibank.api.dto.MoneyDto;
import cz.vsb.minibank.api.dto.MyAccountsResponseDto;
import cz.vsb.minibank.api.dto.NewPaymentRequest;
import cz.vsb.minibank.api.dto.NewPaymentResultDto;
import cz.vsb.minibank.api.dto.PaymentQuoteDto;
import cz.vsb.minibank.application.OwnershipGuard;
import cz.vsb.minibank.application.PaymentOutcome;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FeePolicy;
import cz.vsb.minibank.domain.RiskDecision;
import cz.vsb.minibank.domain.RiskService;
import cz.vsb.minibank.domain.RuleBasedRiskService;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.exceptions.ValidationException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;

import static cz.vsb.minibank.api.AuthHelpers.requireCustomerId;
import static cz.vsb.minibank.api.AuthHelpers.requireUser;

/**
 * REST controller for customer accounts and payment operations.
 */
@RestController
@RequestMapping("/api")
public class PaymentController {

    private final TransferApplicationService transferService;
    private final AccountRepository accounts;

    /**
     * Resolves the customer whose address book {@link #listMyBeneficiaries} answers with, and
     * whose daily ceiling both {@link #listMyAccounts} and {@link #quotePayment} read off the
     * same row.
     *
     * The guard rather than the customer repository directly, so that "who is this caller" is one
     * decision taken in one place: it is the same call the money-moving paths make, and a second
     * route to the same aggregate is a second thing to remember when that decision changes.
     */
    private final OwnershipGuard ownershipGuard;

    /**
     * The one tariff, so the quote below cannot answer differently from the charge.
     */
    private final FeePolicy feePolicy;

    /**
     * Scopes the two reads that make more than one repository call to one connection.
     *
     * The transfer repository is gone from beside it, and the absence is the point rather than
     * tidiness. This controller used to total a day itself, once per account, because the ceiling
     * those totals were measured against was a column on an account. The ceiling belongs to the
     * customer now and so does the total, and the total is read through
     * {@link TransferApplicationService#sentOutTodayBy} - which is the same aggregate, over the
     * same accounts, with the same exclusion of the customer's own IBANs that the refusal applies.
     * A second implementation here would have been a second chance to widen one and not the other.
     */
    private final UnitOfWorkFactory uowFactory;

    /**
     * The rules that decide whether a payment is asked for a one-time code, so that the quote can
     * report the same answer the submit will act on.
     *
     * Constructed here rather than injected, and that is a compromise worth naming. There is no
     * {@code RiskService} bean: every composition root reaches these rules through
     * {@code BootstrapServices}, which builds this same class on every path a running application
     * takes and exposes the instance to nobody. Taking one as a constructor parameter would fail
     * the context at startup; a second constructor would leave Spring with two and no way to pick.
     * What this costs is that a test cannot substitute the rules for this controller - which is
     * the right way round for a quote, whose whole promise is that it answers what the rules will
     * do. What it must not become is a second place where a threshold is written down: this holds
     * the rules, it does not restate them.
     */
    private final RiskService riskService = new RuleBasedRiskService();

    public PaymentController(TransferApplicationService transferService,
                             AccountRepository accounts,
                             OwnershipGuard ownershipGuard,
                             FeePolicy feePolicy,
                             UnitOfWorkFactory uowFactory) {
        this.transferService = transferService;
        this.accounts = accounts;
        this.ownershipGuard = ownershipGuard;
        this.feePolicy = feePolicy;
        this.uowFactory = uowFactory;
    }

    /**
     * The accounts of the currently authenticated customer, and the one day they share.
     *
     * There is no route that takes a customer id. This one reads its subject from the
     * session, so a caller has no way to name somebody else and no ownership check is
     * needed. The twin that took the id in the path was removed rather than guarded:
     * two doors onto the same data are two standing obligations to remember the check.
     *
     * THE DAY COMES BACK HERE rather than through a route of its own. A screen reloads balances
     * because a payment has just moved money, and that same payment moved the day's total; asked
     * separately, the two would be read against each other across a gap only one of them had
     * crossed. See {@link MyAccountsResponseDto}, and {@link DailyOutflowDto} for why one customer
     * with two accounts has one day and not two.
     *
     * TWO READS, DELIBERATELY NOT ONE UNIT OF WORK. The accounts and the ceiling are read together
     * inside the scope below, because they are two lookups answering one question and without a
     * unit of work each of them opens a JDBC connection of its own; there is no pool. The total is
     * not: {@link TransferApplicationService#sentOutTodayBy} opens a unit of work of its own, and
     * the JSON store's lock is taken by begin(), so calling it from inside this scope would
     * deadlock against a lock this thread already holds. It is called after the scope has closed,
     * which also makes it the later of the two readings: a payment settling between them is
     * counted in the total beside balances that predate it, so the day is overstated rather than
     * understated, and a customer is never shown more room than they have.
     */
    @GetMapping("/me/accounts")
    public MyAccountsResponseDto listMyAccounts() {
        int customerId = requireCustomerId();

        List<AccountSummaryDto> summaries;
        Money limit;
        try (UowScope scope = new UowScope(uowFactory.begin())) {
            summaries = accounts.byCustomerId(customerId).stream()
                    .map(this::toAccountSummary)
                    .toList();

            // The ceiling off the caller's own customer row, resolved the way every other
            // ownership decision in this application resolves its subject. Nothing here is being
            // guarded: the id came from the session, and requireCaller is called for the customer
            // it loads.
            limit = ownershipGuard.requireCaller(customerId).dailyLimit();
        }

        Money sentOut = transferService.sentOutTodayBy(customerId);

        return new MyAccountsResponseDto(
                summaries,
                new DailyOutflowDto(MoneyDto.of(sentOut), MoneyDto.of(limit))
        );
    }

    /**
     * Who the caller is signed in as, with the customer behind the login when there is one.
     *
     * Reads its subject from the session like everything else under /me, so there is no id for a
     * caller to substitute and nothing here for anybody else to read.
     *
     * NOT GUARDED BY ROLE, and it is the only route here that is not. An analyst has no customer
     * and gets the two fields that are true of them - their login and their role - with the rest
     * null. Refusing them outright would leave the fraud desk with no way to name the person
     * signed in, which is the same defect on the other screen.
     */
    @GetMapping("/me")
    public MeDto me() {
        User user = requireUser();

        if (!user.isCustomer()) {
            return new MeDto(user.username(), user.role().name(), user.customerId(),
                    null, null, null);
        }

        // A customer row pulls its account ids and its address book with it on both backends, so
        // this is more than one statement and belongs inside a unit of work like the reads above.
        try (UowScope scope = new UowScope(uowFactory.begin())) {
            Customer customer = ownershipGuard.requireCaller(user.customerId());
            Address address = customer.address();

            return new MeDto(
                    user.username(),
                    user.role().name(),
                    customer.id(),
                    customer.name(),
                    customer.email(),
                    address == null ? null : new MeDto.AddressDto(address.street(), address.city())
            );
        }
    }

    /**
     * What a payment would cost and whether it would ask for a code, without creating one.
     *
     * The tariff is a step function of the whole amount - free to 1 000.00, one percent to
     * 10 000.00, one percent and 25.00 above - so a heller past a boundary is ten crowns, and the
     * customer met the number for the first time on the receipt. This route is the one the form
     * asks before sending. It is quoted rather than recomputed in the browser because a copy of a
     * tariff is a copy that drifts, which is the argument {@link PaymentQuoteDto} carries in full.
     *
     * IT PRICES A PAYMENT, IT DOES NOT ACCEPT ONE. Nothing here checks the balance: the account
     * list beside the form already carries it, and a quote that refused an amount the account
     * cannot afford would be answering a question the customer has not asked yet. The daily ceiling
     * is different and is not stepped over: the risk rules refuse an amount past it, so a quote for
     * such an amount is answered exactly as the submit would answer it, before the money moves
     * rather than after.
     *
     * The parameters are optional to Spring and required by this method, following the login
     * route: a missing {@code @RequestParam} makes Spring raise a checked ServletException that
     * the advice cannot catch, which puts a body with no code field on the wire and breaks the
     * error contract to guard a case a caller can trivially reach.
     *
     * @param beneficiaryId the saved payee this payment would go to, or null for an IBAN typed by
     *                      hand. It changes the answer rather than decorating it: an amount above
     *                      the untrusted tier is asked for a code when it is going to a stranger
     *                      and settles at once when it is going to a payee the customer trusts, so
     *                      a quote that could not tell them apart would announce a code for a
     *                      payment that never asks for one. A typed IBAN has no payee row behind
     *                      it and is never trusted, which is what submitPaymentToIban decides at
     *                      creation and what null means here
     * @throws cz.vsb.minibank.domain.exceptions.DailyLimitExceededException when this amount would
     *         take today's outflow past the customer's ceiling
     */
    @GetMapping("/payments/quote")
    public PaymentQuoteDto quotePayment(
            @RequestParam(name = "sourceAccountId", required = false) Integer sourceAccountId,
            @RequestParam(name = "amountCzk", required = false) Double amountCzk,
            @RequestParam(name = "beneficiaryId", required = false) Integer beneficiaryId) {

        int customerId = requireCustomerId();

        if (sourceAccountId == null) {
            throw new ValidationException("sourceAccountId is required");
        }
        if (amountCzk == null) {
            throw new ValidationException("amountCzk is required");
        }

        // The same construction the two submit paths use, so an amount this route prices is an
        // amount they would accept: not more precise than a heller, and greater than zero.
        Money amount = Money.czkPayment(amountCzk);
        Money fee = feePolicy.compute(amount);

        boolean trusted;
        Money dailyLimit;
        Money softDailyThreshold;

        try (UowScope scope = new UowScope(uowFactory.begin())) {
            Customer caller = ownershipGuard.requireCaller(customerId);

            // The source account no longer decides anything this route answers - both limits and
            // the day total below belong to the customer - but it is still resolved, and still
            // against what this caller owns. A quote for somebody else's account is a question
            // this caller may not ask, whatever the answer would have been.
            ownershipGuard.requireOwnedAccount(caller, sourceAccountId);

            trusted = beneficiaryId != null
                    && ownershipGuard.requireOwnedBeneficiary(caller, beneficiaryId).trusted();

            dailyLimit = caller.dailyLimit();
            softDailyThreshold = caller.softDailyThreshold();
        }

        // Outside the scope above, for the reason listMyAccounts gives: this opens a unit of work
        // of its own and the JSON store's lock is taken by begin(). After the ownership checks
        // rather than before them, so a caller naming an account that is not theirs is refused
        // without a day being totalled for them first.
        Money sentOutToday = transferService.sentOutTodayBy(customerId);

        // Zero for the payee total, and the zero is safe rather than convenient: that argument
        // feeds the fraud alert alone, and this route does not report the fraud alert. Telling the
        // customer that a payment is about to be reviewed would tell whoever is holding their
        // credentials where the threshold lies, which is the one reading of these rules that must
        // not reach a screen. The rest of the decision is the customer's own, and is read in full.
        RiskDecision decision = riskService.evaluate(
                trusted,
                amount,
                sentOutToday,
                Money.czk(0.00),
                dailyLimit,
                softDailyThreshold);

        return new PaymentQuoteDto(
                MoneyDto.of(amount),
                MoneyDto.of(fee),
                MoneyDto.of(amount.plus(fee)),
                decision.requireAuthorization()
        );
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
