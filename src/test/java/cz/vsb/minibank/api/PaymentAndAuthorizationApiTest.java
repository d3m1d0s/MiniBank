package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.account.*;
import cz.vsb.minibank.api.dto.payment.*;
import cz.vsb.minibank.application.config.BootstrapServices;
import cz.vsb.minibank.application.auth.SecurityContext;
import cz.vsb.minibank.application.payment.TransferApplicationService;
import cz.vsb.minibank.domain.customer.*;
import cz.vsb.minibank.domain.transfer.*;
import cz.vsb.minibank.domain.exceptions.AccessDeniedException;
import cz.vsb.minibank.domain.exceptions.ConflictException;
import cz.vsb.minibank.domain.exceptions.DailyLimitExceededException;
import cz.vsb.minibank.domain.exceptions.InvalidAmountException;
import cz.vsb.minibank.domain.exceptions.InvalidOtpException;
import cz.vsb.minibank.domain.exceptions.NotFoundException;
import cz.vsb.minibank.domain.exceptions.ValidationException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import cz.vsb.minibank.api.controller.AuthorizationController;
import cz.vsb.minibank.api.controller.PaymentController;

public class PaymentAndAuthorizationApiTest {

    private static final int TEST_CUSTOMER_ID = 2;
    private static final int TEST_ACCOUNT_ID = 101;
    private static final double WAITING_TRANSFER_AMOUNT = 6000.0;

    /**
     * A payee this customer trusts.
     *
     * The quote is the only thing that reads it, and it reads it to answer the one question that
     * splits on trust alone: the same 6 000 asks for a code when it is going to a stranger and
     * settles at once when it is going here.
     */
    private static final int TRUSTED_BENEFICIARY_ID = 5001;

    /** A second customer, so "exists but is not yours" can be told apart from "does not exist". */
    private static final int VICTIM_CUSTOMER_ID = 3;
    private static final int VICTIM_ACCOUNT_ID = 202;
    private static final int VICTIM_BENEFICIARY_ID = 5002;

    @TempDir
    Path tempDir;

    private PaymentController paymentController;
    private AuthorizationController authorizationController;
    private AccountRepository accounts;
    private TransferRepository transfers;
    private TransferApplicationService transferService;
    private int victimWaitingTransfer;

    @BeforeEach
    void setup() {
        Bootstrap infra = new Bootstrap(tempDir.resolve("data.json").toString());

        // Ensure that customer=2 and account=101 exist for tests
        CustomerRepository customers = infra.customers;

        // 1) Ensure customer 2
        Customer customer = customers.byId(TEST_CUSTOMER_ID)
                .orElseGet(() -> {
                    Customer c = new Customer(
                            TEST_CUSTOMER_ID,
                            "Test Customer 2",
                            "test2@example.com",
                            new Address("Test Street 2", "Ostrava"),
                            // The hard daily ceiling, matching the demo. WAITING_TRANSFER_AMOUNT
                            // is 6 000 and createWaitingTransferForCustomer2 feeds nine tests; at
                            // 5 000 every one of them is refused outright instead of waiting.
                            Money.czk(40_000)
                    );
                    customers.save(c);
                    return c;
                });

        // 2) Ensure account 101 is linked to this customer
        boolean hasTestAccount = infra.accounts.byCustomerId(TEST_CUSTOMER_ID).stream()
                .anyMatch(a -> a.id() == TEST_ACCOUNT_ID);

        if (!hasTestAccount) {
            Account acc = new Account(
                    TEST_ACCOUNT_ID,
                    new IBAN("CZ6508000000192000145399"),
                    Money.czk(20_000)
            );
            infra.accounts.save(acc);
            customer.addAccountId(TEST_ACCOUNT_ID);
            customers.save(customer);
        }

        infra.customers.saveBeneficiary(TEST_CUSTOMER_ID, new Beneficiary(
                TRUSTED_BENEFICIARY_ID, "Trusted payee",
                new IBAN("CZ1301000000000098765432"), true));

        // 3) Simulate logged-in customer with customerId = 2 via SecurityContext
        byte[] dummy = new byte[0];
        User user = new User(
                1,
                "test-customer",
                dummy,
                dummy,
                UserRole.CUSTOMER,
                TEST_CUSTOMER_ID
        );
        SecurityContext.setCurrentUser(user);

        // Initialize shared test dependencies
        accounts = infra.accounts;
        transfers = infra.transfers;

        BootstrapServices services = new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory
        );

        transferService = services.transferService;

        // A second customer with an account and a beneficiary of their own, plus one pending
        // transfer they created themselves. This is what customer 2 must not be able to touch.
        Customer victim = new Customer(VICTIM_CUSTOMER_ID, "Victim Customer 3",
                "victim3@example.com", new Address("Test Street 3", "Ostrava"),
                Money.czk(40_000));
        victim.addAccountId(VICTIM_ACCOUNT_ID);
        infra.customers.save(victim);
        infra.accounts.save(new Account(VICTIM_ACCOUNT_ID, new IBAN("CZ4308000000192000145407"),
                Money.czk(20_000)));
        infra.customers.saveBeneficiary(VICTIM_CUSTOMER_ID, new Beneficiary(
                VICTIM_BENEFICIARY_ID, "Victim's payee",
                new IBAN("CZ9608000000192000145423"), true));

        victimWaitingTransfer = transferService.submitPaymentToIban(
                VICTIM_CUSTOMER_ID, VICTIM_ACCOUNT_ID, "CZ2001000000000012345678",
                WAITING_TRANSFER_AMOUNT, "victim's own").transferId();

        paymentController = new PaymentController(transferService, accounts,
                services.ownershipGuard, services.feePolicy, infra.uowFactory);

        authorizationController = new AuthorizationController(
                transferService,
                accounts,
                transfers,
                services.feePolicy,
                services.ownershipGuard,
                infra.uowFactory
        );
    }

    private int createWaitingTransferForCustomer2() {
        List<AccountSummaryDto> accList = paymentController.listMyAccounts().accounts();
        assertFalse(accList.isEmpty(), "Customer 2 should have at least one account");
        AccountSummaryDto acc = accList.get(0);

        NewPaymentRequest req = new NewPaymentRequest(
                acc.id(),
                "CZ2001000000000012345678",
                null,
                WAITING_TRANSFER_AMOUNT,
                "Test waiting transfer"
        );

        NewPaymentResultDto result = paymentController.createPayment(req).getBody();
        assertNotNull(result, "Result body must not be null");

        int transferId = result.transferId();
        Transfer t = transfers.byId(transferId)
                .orElseThrow(() -> new AssertionError("New transfer not found in repository"));

        assertEquals(
                TransferStatus.WAITING_AUTH,
                t.status(),
                "Precondition: newly created transfer must be in WAITING_AUTH"
        );

        return transferId;
    }

    @Test
    void listMyAccounts_returnsTheSessionCustomersAccount() {
        MyAccountsResponseDto response = paymentController.listMyAccounts();
        List<AccountSummaryDto> list = response.accounts();
        assertFalse(list.isEmpty(), "Expected accounts for customer 2");
        AccountSummaryDto acc = list.get(0);
        assertEquals(TEST_ACCOUNT_ID, acc.id());
        assertTrue(acc.iban().startsWith("CZ"));

        // The day travels with the accounts rather than on a route of its own, and it is one
        // day for the customer: nothing has settled yet, and the ceiling is the one on their
        // own row.
        assertEquals("0.00", response.today().sentOut().amount());
        assertEquals("CZK", response.today().sentOut().currency());
        assertEquals("40000.00", response.today().limit().amount());
        assertEquals("CZK", response.today().limit().currency());
    }

    @Test
    void listWaitingTransfers_forCustomer2_containsNewWaitingTransfer() {
        int transferId = createWaitingTransferForCustomer2();

        // Explicitly the first page and a size that covers the fixture, so this asserts about the
        // list and not about where the default page size happens to fall.
        List<WaitingTransferItemDto> waiting = authorizationController.listMyWaiting(0, 25).items();
        assertFalse(waiting.isEmpty(), "Expected at least one waiting transfer for customer 2");

        WaitingTransferItemDto item = waiting.stream()
                .filter(t -> t.id() == transferId)
                .findFirst()
                .orElse(null);
        assertNotNull(item, "Expected newly created waiting transfer in the list");

        // Named rather than merely non-empty: the failure this pins is a well-formed string
        // that happens to be Object's identity hash, which any looser assertion accepts.
        assertEquals("CARD", item.authMethod(),
                "The waiting list must name the authorization method, not the Payment object");
    }

    @Test
    void transferDetails_forNewWaitingTransfer_returnsConsistentInfo() {
        int transferId = createWaitingTransferForCustomer2();

        TransferDetailsDto details = authorizationController.transferDetails(transferId);

        assertEquals(transferId, details.id());
        assertEquals("CZ2001000000000012345678", details.toIban());
        // Exact on both halves now that the amount and its currency are separate fields. This
        // was a startsWith on one formatted string, which passed whatever the currency was and
        // whatever followed the digits.
        assertEquals(String.format(Locale.US, "%.2f", WAITING_TRANSFER_AMOUNT),
                details.amount().amount());
        assertEquals("CZK", details.amount().currency());
        assertEquals("WAITING_AUTH", details.status());
        assertNotNull(details.fromIban());
        assertTrue(details.fromIban().startsWith("CZ"));
        assertEquals("CARD", details.authMethod(),
                "Transfer details must name the authorization method, not the Payment object");
    }

    @Test
    void authorizePayment_withInvalidOtp_isRefusedAndSpendsOneAttempt() {
        int transferId = createWaitingTransferForCustomer2();

        Transfer before = transfers.byId(transferId)
                .orElseThrow(() -> new AssertionError("Transfer not found before auth"));
        assertEquals(
                TransferStatus.WAITING_AUTH,
                before.status(),
                "Precondition: transfer must start as WAITING_AUTH"
        );

        AuthorizePaymentRequest req = new AuthorizePaymentRequest("WRONG_OTP");

        assertThrows(InvalidOtpException.class,
                () -> authorizationController.authorize(transferId, req));

        Transfer after = transfers.byId(transferId)
                .orElseThrow(() -> new AssertionError("Transfer not found after auth"));

        // The refusal is raised after the commit, so the spent attempt must have survived it.
        // If a refactor ever moves the throw above uow.commit(), this is what notices: the
        // three-attempt limit would silently become unlimited.
        assertEquals(TransferStatus.WAITING_AUTH, after.status());
        assertEquals(1, after.authAttempts());
    }

    /**
     * The third wrong code is not an error: the transfer is declined and that outcome is
     * reported in a 200 body, which is what both frontends already render.
     */
    @Test
    void authorizePayment_withTheLastInvalidOtp_declinesInsteadOfThrowing() {
        int transferId = createWaitingTransferForCustomer2();
        AuthorizePaymentRequest req = new AuthorizePaymentRequest("WRONG_OTP");

        assertThrows(InvalidOtpException.class, () -> authorizationController.authorize(transferId, req));
        assertThrows(InvalidOtpException.class, () -> authorizationController.authorize(transferId, req));

        AuthorizePaymentResult result = authorizationController.authorize(transferId, req);

        assertEquals(TransferStatus.DECLINED.name(), result.status());
        assertEquals("Too many invalid OTP attempts", result.declineReason());
        assertEquals(3, transfers.byId(transferId).orElseThrow().authAttempts());
    }

    @Test
    void authorizePayment_withoutAnOtp_isRejectedWithoutSpendingAnAttempt() {
        int transferId = createWaitingTransferForCustomer2();

        assertThrows(ValidationException.class,
                () -> authorizationController.authorize(transferId, new AuthorizePaymentRequest(null)));
        assertThrows(ValidationException.class,
                () -> authorizationController.authorize(transferId, new AuthorizePaymentRequest("  ")));

        Transfer after = transfers.byId(transferId).orElseThrow();
        assertEquals(0, after.authAttempts(), "An omitted field must not cost an attempt");
        assertEquals(TransferStatus.WAITING_AUTH, after.status());
    }

    @Test
    void authorizePayment_withAnUnknownId_isNotFound() {
        assertThrows(NotFoundException.class,
                () -> authorizationController.authorize(999_999, new AuthorizePaymentRequest("0000")));
    }

    /**
     * Authorizing the same transfer twice is a state conflict, not a server fault. It used
     * to be a bare RuntimeException, so it answered 500.
     */
    @Test
    void authorizePayment_onATransferThatIsNotWaiting_isAConflict() {
        int transferId = createWaitingTransferForCustomer2();
        authorizationController.cancel(transferId);

        assertThrows(ConflictException.class,
                () -> authorizationController.authorize(transferId, new AuthorizePaymentRequest("0000")));
    }

    @Test
    void cancelPayment_withAnUnknownId_isNotFound() {
        assertThrows(NotFoundException.class, () -> authorizationController.cancel(999_999));
    }

    @Test
    void transferDetails_withAnUnknownId_isNotFound() {
        assertThrows(NotFoundException.class, () -> authorizationController.transferDetails(999_999));
    }

    /**
     * A source account id the caller made up is the caller's mistake, not ours.
     *
     * This case alone cannot show that the ownership rule works: 999999 belongs to nobody, so it is refused
     * with or without the ownership guard. The "exists but is another customer's" half is
     * pinned by MoneyPathOwnershipTest and, byte for byte on the wire, by HttpErrorContractTest.
     */
    @Test
    void createPayment_withAnUnknownSourceAccount_isNotFound() {
        NewPaymentRequest req = new NewPaymentRequest(
                999_999, "CZ2001000000000012345678", null, 1000.0, "no such account");

        assertThrows(NotFoundException.class, () -> paymentController.createPayment(req));
    }

    @Test
    void cancelPayment_setsStatusDeclinedForNewWaitingTransfer() {
        int transferId = createWaitingTransferForCustomer2();

        Transfer before = transfers.byId(transferId)
                .orElseThrow(() -> new AssertionError("Transfer not found before cancel"));
        assertEquals(
                TransferStatus.WAITING_AUTH,
                before.status(),
                "Precondition: transfer must start as WAITING_AUTH"
        );

        AuthorizePaymentResult result = authorizationController.cancel(transferId);

        Transfer after = transfers.byId(transferId)
                .orElseThrow(() -> new AssertionError("Transfer not found after cancel"));

        assertEquals(TransferStatus.DECLINED.name(), result.status());
        assertEquals(TransferStatus.DECLINED, after.status());
    }

    // --------------------------------------------------------------------- ownership

    /**
     * The same four escalations MoneyPathOwnershipTest pins at the service, asserted here
     * through the controllers so the HTTP layer is shown to carry the session identity into
     * the service rather than dropping it.
     */
    @Test
    void createPayment_fromAnotherCustomersAccount_isNotFoundAndMovesNothing() {
        NewPaymentRequest req = new NewPaymentRequest(
                VICTIM_ACCOUNT_ID, "CZ2001000000000012345678", null, 900.0, "not my account");

        assertThrows(NotFoundException.class, () -> paymentController.createPayment(req));

        assertVictimUntouched();
    }

    /** Beneficiary ownership, through the service the console and DemoRunner also call. */
    @Test
    void submitPaymentByBeneficiary_withAnotherCustomersBeneficiary_isNotFound() {
        assertThrows(NotFoundException.class, () -> transferService.submitPaymentByBeneficiary(
                TEST_CUSTOMER_ID, TEST_ACCOUNT_ID, VICTIM_BENEFICIARY_ID, 900.0, "not my payee"));

        assertVictimUntouched();
    }

    @Test
    void authorizePayment_onAnotherCustomersTransfer_isNotFoundAndSpendsNoAttempt() {
        assertThrows(NotFoundException.class, () -> authorizationController.authorize(
                victimWaitingTransfer, new AuthorizePaymentRequest("0000")));

        assertVictimUntouched();
    }

    @Test
    void cancelPayment_onAnotherCustomersTransfer_isNotFoundAndWritesNoAuditReason() {
        assertThrows(NotFoundException.class,
                () -> authorizationController.cancel(victimWaitingTransfer));

        assertVictimUntouched();
    }

    /**
     * Reading a stranger's transfer disclosed the source IBAN and its live balance, which
     * is the reconnaissance step that made the write-side escalation usable with no prior
     * knowledge. The refusal has to be the same as for an id that does not exist.
     */
    @Test
    void transferDetails_ofAnotherCustomersTransfer_isNotFound() {
        NotFoundException foreign = assertThrows(NotFoundException.class,
                () -> authorizationController.transferDetails(victimWaitingTransfer));
        NotFoundException absent = assertThrows(NotFoundException.class,
                () -> authorizationController.transferDetails(999_999));

        assertEquals(absent.getClass(), foreign.getClass(),
                "A stranger's transfer must be refused exactly like one that does not exist");
        assertVictimUntouched();
    }

    @Test
    void transferDetails_ofTheOwnersOwnTransfer_stillWorks() {
        int mine = createWaitingTransferForCustomer2();

        var details = authorizationController.transferDetails(mine);

        assertEquals(mine, details.id());
        assertTrue(details.fromIban().startsWith("CZ"));
    }

    /**
     * These two handlers used to read no identity at all, so a FRAUD_ANALYST session
     * could drive them. The refusal is a role denial, raised before the transfer id is used.
     */
    @Test
    void authorizeAndCancel_asAFraudAnalyst_areRefusedByRole() {
        SecurityContext.setCurrentUser(new User(
                2, "fraud", new byte[0], new byte[0], UserRole.FRAUD_ANALYST, null));

        assertThrows(AccessDeniedException.class, () -> authorizationController.authorize(
                victimWaitingTransfer, new AuthorizePaymentRequest("0000")));
        assertThrows(AccessDeniedException.class,
                () -> authorizationController.cancel(victimWaitingTransfer));

        assertVictimUntouched();
    }

    /** A refused request must leave the victim exactly as it found them. */
    private void assertVictimUntouched() {
        assertEquals(Money.czk(20_000), accounts.byId(VICTIM_ACCOUNT_ID).orElseThrow().balance(),
                "The victim's balance must not move");

        Transfer waiting = transfers.byId(victimWaitingTransfer).orElseThrow();
        assertEquals(TransferStatus.WAITING_AUTH, waiting.status());
        assertEquals(0, waiting.authAttempts(),
                "A stranger must not be able to spend the victim's OTP attempts");
        assertNull(waiting.declineReason(),
                "A stranger must not be able to write the victim's audit reason");
    }

    @Test
    void createPaymentToIban_createsTransferAndReturnsResult() {
        List<AccountSummaryDto> beforeAccounts = paymentController.listMyAccounts().accounts();
        assertFalse(beforeAccounts.isEmpty(), "Customer 2 should have at least one account");
        AccountSummaryDto accBefore = beforeAccounts.get(0);

        NewPaymentRequest req = new NewPaymentRequest(
                accBefore.id(),
                "CZ2001000000000012345678",
                null,
                1000.0,
                "JUnit REST payment"
        );

        NewPaymentResultDto result = paymentController.createPayment(req).getBody();
        assertNotNull(result, "Result body must not be null");
        assertTrue(result.transferId() > 0, "Transfer id must be > 0");
        assertNotNull(result.status());
        assertNotNull(result.newBalance());
    }

    // ------------------------------------------------------- what a transfer read says about itself

    /**
     * A settled payment says when the money moved, what the customer wrote on it, and what it
     * still owes the network.
     *
     * All three were stored and none reached a screen. The settlement instant is the only record
     * of when the money actually left - it is a different number from the creation instant on
     * every payment an analyst held - and the message is what the customer typed into the form and
     * could never read back afterwards.
     */
    @Test
    void aSettledPaymentSaysWhenItMovedWhatWasWrittenOnItAndWhatItOwesTheNetwork() {
        NewPaymentRequest req = new NewPaymentRequest(
                TEST_ACCOUNT_ID, "CZ2001000000000012345678", null, 1_000.0, "Rent for August");

        int transferId = paymentController.createPayment(req).getBody().transferId();

        TransferDetailsDto details = authorizationController.transferDetails(transferId);

        assertEquals("SENT", details.status(), "Precondition: 1 000 settles without a code");
        assertNotNull(details.settledAt(),
                "a payment that has settled has an instant at which it did");
        assertEquals("Rent for August", details.message(),
                "the customer's own reference has been accepted by the creation request since"
                        + " long before it was stored, and this is where it is read back");
        assertEquals("PENDING", details.dispatchState(),
                "this beneficiary is not an account of this bank, so the money left it and the"
                        + " network is owed a dispatch that no gateway has been handed yet");
    }

    /**
     * A payment that has not settled owes the network nothing and has no settlement instant, and
     * the wire says so with nulls rather than with a third value.
     */
    @Test
    void aWaitingPaymentHasNoSettlementInstantAndOwesTheNetworkNothing() {
        int transferId = createWaitingTransferForCustomer2();

        TransferDetailsDto details = authorizationController.transferDetails(transferId);

        assertNull(details.settledAt(), "nothing has moved, so there is no instant at which it did");
        assertNull(details.dispatchState(),
                "a payment that has not settled owes the network nothing; null here is not"
                        + " 'stayed in the bank', which is what toIbanInBank answers");
    }

    /**
     * A transfer with no authorization method recorded says null, not an empty string.
     *
     * One wire field used to arrive in three shapes: "CARD" from a held payment, null from the
     * fraud desk's own producer of the same field, and "" from here. A client cannot tell the
     * third from the second without knowing which endpoint it came from, and both front ends
     * printed an empty cell where a settled payment simply has no method on record.
     */
    @Test
    void aTransferWithNoAuthorizationMethodSaysNullAndNotAnEmptyString() {
        NewPaymentRequest req = new NewPaymentRequest(
                TEST_ACCOUNT_ID, "CZ2001000000000012345678", null, 1_000.0, "settles at once");

        int transferId = paymentController.createPayment(req).getBody().transferId();

        assertNull(authorizationController.transferDetails(transferId).authMethod(),
                "this payment settled without ever asking for a code, so it has no method to"
                        + " name, and absent has one spelling on this wire");
    }

    // ------------------------------------------------------------------------------- the fee quote

    /**
     * The tariff is answered before the money moves, and it is answered by the server.
     *
     * 1 500.00 pays one percent. The number matters less than where it comes from: a copy of this
     * schedule in the browser is a copy that drifts, and the customer met the fee for the first
     * time on the receipt.
     */
    @Test
    void theQuoteAnswersTheFeeAndTheTotalBeforeAnythingIsSent() {
        PaymentQuoteDto quote = paymentController.quotePayment(TEST_ACCOUNT_ID, 1_500.00, null);

        assertEquals("1500.00", quote.amount().amount());
        assertEquals("15.00", quote.fee().amount(), "one percent of the whole amount");
        assertEquals("1515.00", quote.total().amount(),
                "what leaves the account is the amount plus the fee, added once and on the"
                        + " server");
        assertEquals("CZK", quote.total().currency());
    }

    /**
     * The boundary the schedule turns on is visible from the route.
     *
     * This is the whole reason a quote exists. The tariff is a step on the WHOLE amount, so one
     * heller past 1 000.00 turns a free payment into a ten crown fee, and rounding an amount up
     * costs a thousand times the difference. A customer who can see it before pressing Send can
     * choose; one who meets it on the receipt cannot.
     */
    @Test
    void theQuoteShowsTheStepAtTheFreeBoundary() {
        assertEquals("0.00", paymentController.quotePayment(TEST_ACCOUNT_ID, 1_000.00, null)
                .fee().amount(), "1 000.00 belongs to the free tier");
        assertEquals("10.00", paymentController.quotePayment(TEST_ACCOUNT_ID, 1_000.01, null)
                .fee().amount(), "and one heller past it pays one percent of the whole amount");
    }

    /**
     * The quote says whether a code will be asked for, and the saved payee is what decides it.
     *
     * Both calls are the same amount out of the same account on the same day. Without the payee
     * the route would have to assume the stranger, and a form that announced a code for every
     * payment above the tier would be wrong about every payment to a trusted payee.
     */
    @Test
    void theQuoteSaysWhetherACodeWillBeAskedForAndTheSavedPayeeDecidesIt() {
        assertTrue(paymentController.quotePayment(TEST_ACCOUNT_ID, 6_000.00, null)
                        .authorizationRequired(),
                "6 000 to an account number typed by hand is above the untrusted tier, and a"
                        + " typed number has no payee row behind it and never can be trusted");

        assertFalse(paymentController.quotePayment(TEST_ACCOUNT_ID, 6_000.00, TRUSTED_BENEFICIARY_ID)
                        .authorizationRequired(),
                "the same amount to a payee this customer trusts settles at once, and the quote"
                        + " has to say what the submit will do rather than what it might");
    }

    /**
     * The quote refuses what the submit would refuse, over the same ceiling.
     *
     * A quote that answered a price for a payment the bank will not accept would be a form that
     * says 145.00 and then a screen that says no.
     */
    @Test
    void aQuoteOverTheDailyCeilingIsRefusedExactlyAsThePaymentWouldBe() {
        assertThrows(DailyLimitExceededException.class,
                () -> paymentController.quotePayment(TEST_ACCOUNT_ID, 50_000.00, null));
    }

    /**
     * The quote is scoped like every other route under a session: an account the caller does not
     * own is refused exactly like one that does not exist.
     *
     * Without it this route would answer, for any account id, whether that account's day is close
     * to its ceiling - which is a fact about somebody else's spending.
     */
    @Test
    void aQuoteOnAnotherCustomersAccountIsNotFound() {
        assertThrows(NotFoundException.class,
                () -> paymentController.quotePayment(VICTIM_ACCOUNT_ID, 100.00, null));
        assertThrows(NotFoundException.class,
                () -> paymentController.quotePayment(TEST_ACCOUNT_ID, 100.00, VICTIM_BENEFICIARY_ID));

        assertVictimUntouched();
    }

    /**
     * A missing parameter is refused by this method rather than by Spring.
     *
     * A required {@code @RequestParam} raises a checked ServletException that the error advice
     * cannot catch, so the caller would get a body with no code field in it and the error contract
     * would have a hole in exactly the place a form is most likely to find one.
     */
    @Test
    void aQuoteWithAMissingParameterIsAValidationError() {
        assertThrows(ValidationException.class,
                () -> paymentController.quotePayment(null, 1_000.00, null));
        assertThrows(ValidationException.class,
                () -> paymentController.quotePayment(TEST_ACCOUNT_ID, null, null));
    }

    /**
     * An amount the submit would not accept is not priced either: the two doors take the same
     * amounts, or the form would quote a fee for something that cannot be sent.
     */
    @Test
    void aQuoteForAnAmountThePaymentWouldRefuseIsRefusedToo() {
        assertThrows(InvalidAmountException.class,
                () -> paymentController.quotePayment(TEST_ACCOUNT_ID, 0.0, null));
        assertThrows(InvalidAmountException.class,
                () -> paymentController.quotePayment(TEST_ACCOUNT_ID, 10.001, null));
    }

    // ----------------------------------------------------------------- what the day's ceiling means

    /**
     * The ceiling and the total it is measured against travel together, and neither hangs off an
     * account.
     *
     * The limit alone is a number with nothing to compare it to. The customer sees the same amount
     * settle in the morning and ask for a code in the afternoon, and the only thing that changed is
     * a total no screen has ever shown. Both readings come back beside the accounts rather than
     * inside them, because one person has one day however many accounts they hold.
     */
    @Test
    void theResponseCarriesTheCeilingAndWhatHasGoneTodayAndNoAccountCarriesEither() {
        MyAccountsResponseDto before = paymentController.listMyAccounts();

        assertEquals("40000.00", before.today().limit().amount(),
                "the hard ceiling on one day's outflow, off the customer's own row");
        assertEquals("0.00", before.today().sentOut().amount(),
                "nothing has settled out of this customer yet today");

        paymentController.createPayment(new NewPaymentRequest(
                TEST_ACCOUNT_ID, "CZ2001000000000012345678", null, 1_000.0, "counts today"));

        MyAccountsResponseDto after = paymentController.listMyAccounts();

        assertEquals("1000.00", after.today().sentOut().amount(),
                "a payment that settled today counts against today, fees excluded");
        assertEquals("40000.00", after.today().limit().amount(),
                "and the ceiling itself does not move when a payment does");

        // The soft tier is deliberately absent from this response: the bank-wide default is not
        // this application's to print, and the question a screen asks about it is already answered
        // per payment by the quote's authorizationRequired.
        assertEquals(3, AccountSummaryDto.class.getRecordComponents().length,
                "an account summary carries id, iban and balance, and no day of its own");
    }

    /**
     * A payment waiting for its code has moved nothing, so it counts against nothing.
     *
     * This is the same rule the refusal itself keeps, and it has to be: a form that counted
     * pending payments would show a budget the bank does not enforce, and every abandoned payment
     * would shrink the customer's day until it expired.
     */
    @Test
    void aPaymentWaitingForItsCodeHasNotBeenSpentYet() {
        createWaitingTransferForCustomer2();

        assertEquals("0.00", paymentController.listMyAccounts().today().sentOut().amount(),
                "nothing has left the customer, so nothing has been spent");
    }

    // -------------------------------------------------------------------------- who is signed in

    /**
     * The browser can name the person signed in.
     *
     * The console has been able to do this since it was written - it holds the customer aggregate
     * directly - and the product had no route that returned one, so both front ends printed a
     * login name where a person's name belongs.
     */
    @Test
    void meNamesTheCustomerBehindTheLoginAndNotOnlyTheLogin() {
        MeDto me = paymentController.me();

        assertEquals("test-customer", me.username());
        assertEquals("CUSTOMER", me.role());
        assertEquals(TEST_CUSTOMER_ID, me.customerId());
        assertEquals("Test Customer 2", me.name());
        assertEquals("test2@example.com", me.email());
        assertNotNull(me.address(), "this customer has an address on file");
        assertEquals("Test Street 2", me.address().street());
        assertEquals("Ostrava", me.address().city());
    }

    /**
     * A user who is not a customer is answered rather than refused.
     *
     * An analyst has no customer row and that is not a fault. Refusing them would leave the fraud
     * desk with no way to name the person signed in, which is the same defect on the other screen.
     */
    @Test
    void meAnswersAnAnalystWithTheTwoFactsThatAreTrueOfThem() {
        SecurityContext.setCurrentUser(new User(
                2, "fraud", new byte[0], new byte[0], UserRole.FRAUD_ANALYST, null));

        MeDto me = paymentController.me();

        assertEquals("fraud", me.username());
        assertEquals("FRAUD_ANALYST", me.role());
        assertNull(me.customerId());
        assertNull(me.name());
        assertNull(me.email());
        assertNull(me.address());
    }
}
