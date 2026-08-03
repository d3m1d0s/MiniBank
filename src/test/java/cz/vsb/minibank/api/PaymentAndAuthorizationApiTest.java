package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.*;
import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.exceptions.AccessDeniedException;
import cz.vsb.minibank.domain.exceptions.ConflictException;
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

public class PaymentAndAuthorizationApiTest {

    private static final int TEST_CUSTOMER_ID = 2;
    private static final int TEST_ACCOUNT_ID = 101;
    private static final double WAITING_TRANSFER_AMOUNT = 6000.0;

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
                            new Address("Test Street 2", "Ostrava")
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
                    Money.czk(20_000),
                    // The hard daily ceiling, matching the demo. WAITING_TRANSFER_AMOUNT is
                    // 6 000 and createWaitingTransferForCustomer2 feeds nine tests; at the old
                    // 5 000 every one of them is refused outright instead of waiting.
                    Money.czk(40_000)
            );
            infra.accounts.save(acc);
            customer.addAccountId(TEST_ACCOUNT_ID);
            customers.save(customer);
        }

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
                "victim3@example.com", new Address("Test Street 3", "Ostrava"));
        victim.addAccountId(VICTIM_ACCOUNT_ID);
        infra.customers.save(victim);
        infra.accounts.save(new Account(VICTIM_ACCOUNT_ID, new IBAN("CZ4308000000192000145407"),
                Money.czk(20_000), Money.czk(40_000)));
        infra.customers.saveBeneficiary(VICTIM_CUSTOMER_ID, new Beneficiary(
                VICTIM_BENEFICIARY_ID, "Victim's payee",
                new IBAN("CZ9608000000192000145423"), true));

        victimWaitingTransfer = transferService.submitPaymentToIban(
                VICTIM_CUSTOMER_ID, VICTIM_ACCOUNT_ID, "CZ2001000000000012345678",
                WAITING_TRANSFER_AMOUNT, "victim's own");

        paymentController = new PaymentController(
                transferService,
                accounts,
                transfers,
                services.feePolicy
        );

        authorizationController = new AuthorizationController(
                transferService,
                accounts,
                transfers,
                services.feePolicy,
                services.ownershipGuard
        );
    }

    private int createWaitingTransferForCustomer2() {
        List<AccountSummaryDto> accList = paymentController.listMyAccounts();
        assertFalse(accList.isEmpty(), "Customer 2 should have at least one account");
        AccountSummaryDto acc = accList.get(0);

        NewPaymentRequest req = new NewPaymentRequest(
                acc.id(),
                "CZ2001000000000012345678",
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
        List<AccountSummaryDto> list = paymentController.listMyAccounts();
        assertFalse(list.isEmpty(), "Expected accounts for customer 2");
        AccountSummaryDto acc = list.get(0);
        assertEquals(TEST_ACCOUNT_ID, acc.id());
        assertTrue(acc.iban().startsWith("CZ"));
    }

    @Test
    void listWaitingTransfers_forCustomer2_containsNewWaitingTransfer() {
        int transferId = createWaitingTransferForCustomer2();

        List<WaitingTransferItemDto> waiting = authorizationController.listMyWaiting();
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
        String expectedPrefix = String.format(Locale.US, "%.2f", WAITING_TRANSFER_AMOUNT);
        assertTrue(
                details.amount().startsWith(expectedPrefix),
                "Expected amount to start with " + expectedPrefix
        );
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
     * This case alone cannot show that A3 works: 999999 belongs to nobody, so it is refused
     * with or without the ownership guard. The "exists but is another customer's" half is
     * pinned by MoneyPathOwnershipTest and, byte for byte on the wire, by HttpErrorContractTest.
     */
    @Test
    void createPayment_withAnUnknownSourceAccount_isNotFound() {
        NewPaymentRequest req = new NewPaymentRequest(
                999_999, "CZ2001000000000012345678", 1000.0, "no such account");

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

    // ------------------------------------------------------------------ A3: ownership

    /**
     * The same four escalations MoneyPathOwnershipTest pins at the service, asserted here
     * through the controllers so the HTTP layer is shown to carry the session identity into
     * the service rather than dropping it.
     */
    @Test
    void createPayment_fromAnotherCustomersAccount_isNotFoundAndMovesNothing() {
        NewPaymentRequest req = new NewPaymentRequest(
                VICTIM_ACCOUNT_ID, "CZ2001000000000012345678", 900.0, "not my account");

        assertThrows(NotFoundException.class, () -> paymentController.createPayment(req));

        assertVictimUntouched();
    }

    /** B17, through the service the console and DemoRunner also call. */
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
     * A4: reading a stranger's transfer disclosed the source IBAN and its live balance, which
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
     * N15: before A3 these two handlers read no identity at all, so a FRAUD_ANALYST session
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
        List<AccountSummaryDto> beforeAccounts = paymentController.listMyAccounts();
        assertFalse(beforeAccounts.isEmpty(), "Customer 2 should have at least one account");
        AccountSummaryDto accBefore = beforeAccounts.get(0);

        NewPaymentRequest req = new NewPaymentRequest(
                accBefore.id(),
                "CZ2001000000000012345678",
                1000.0,
                "JUnit REST payment"
        );

        NewPaymentResultDto result = paymentController.createPayment(req).getBody();
        assertNotNull(result, "Result body must not be null");
        assertTrue(result.transferId() > 0, "Transfer id must be > 0");
        assertNotNull(result.status());
        assertNotNull(result.newBalance());
    }
}
