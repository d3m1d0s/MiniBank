package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.*;
import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.AfterEach;
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

    @TempDir
    Path tempDir;

    private PaymentController paymentController;
    private AuthorizationController authorizationController;
    private AccountRepository accounts;
    private TransferRepository transfers;

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
                    Money.czk(5_000)
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

        TransferApplicationService transferService = services.transferService;

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
                services.feePolicy
        );
    }

    @AfterEach
    void tearDown() {
        // SecurityContext is a static ThreadLocal shared by the whole suite
        SecurityContext.clear();
    }

    private int createWaitingTransferForCustomer2() {
        List<AccountSummaryDto> accList = paymentController.listAccounts(TEST_CUSTOMER_ID);
        assertFalse(accList.isEmpty(), "Customer 2 should have at least one account");
        AccountSummaryDto acc = accList.get(0);

        NewPaymentRequest req = new NewPaymentRequest(
                TEST_CUSTOMER_ID, // field is still present in the record but controller may ignore it
                acc.id(),
                "CZ0201000000000012345678",
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
    void listAccounts_forExistingCustomer2_returnsAccount101() {
        List<AccountSummaryDto> list = paymentController.listAccounts(TEST_CUSTOMER_ID);
        assertFalse(list.isEmpty(), "Expected accounts for customer 2");
        AccountSummaryDto acc = list.get(0);
        assertEquals(TEST_ACCOUNT_ID, acc.id());
        assertTrue(acc.iban().startsWith("CZ"));
    }

    @Test
    void listWaitingTransfers_forCustomer2_containsNewWaitingTransfer() {
        int transferId = createWaitingTransferForCustomer2();

        List<WaitingTransferItemDto> waiting = authorizationController.listWaiting(TEST_CUSTOMER_ID);
        assertFalse(waiting.isEmpty(), "Expected at least one waiting transfer for customer 2");

        boolean hasNew = waiting.stream().anyMatch(t -> t.id() == transferId);
        assertTrue(hasNew, "Expected newly created waiting transfer in the list");
    }

    @Test
    void transferDetails_forNewWaitingTransfer_returnsConsistentInfo() {
        int transferId = createWaitingTransferForCustomer2();

        TransferDetailsDto details = authorizationController.transferDetails(transferId);

        assertEquals(transferId, details.id());
        assertEquals("CZ0201000000000012345678", details.toIban());
        String expectedPrefix = String.format(Locale.US, "%.2f", WAITING_TRANSFER_AMOUNT);
        assertTrue(
                details.amount().startsWith(expectedPrefix),
                "Expected amount to start with " + expectedPrefix
        );
        assertEquals("WAITING_AUTH", details.status());
        assertNotNull(details.fromIban());
        assertTrue(details.fromIban().startsWith("CZ"));
    }

    @Test
    void authorizePayment_withInvalidOtp_keepsTransferWaitingAuth() {
        int transferId = createWaitingTransferForCustomer2();

        Transfer before = transfers.byId(transferId)
                .orElseThrow(() -> new AssertionError("Transfer not found before auth"));
        assertEquals(
                TransferStatus.WAITING_AUTH,
                before.status(),
                "Precondition: transfer must start as WAITING_AUTH"
        );

        AuthorizePaymentRequest req = new AuthorizePaymentRequest("WRONG_OTP");

        AuthorizePaymentResult result = authorizationController.authorize(transferId, req);

        Transfer after = transfers.byId(transferId)
                .orElseThrow(() -> new AssertionError("Transfer not found after auth"));

        assertEquals(TransferStatus.WAITING_AUTH.name(), result.status());
        assertEquals(TransferStatus.WAITING_AUTH, after.status());
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

    @Test
    void createPaymentToIban_createsTransferAndReturnsResult() {
        List<AccountSummaryDto> beforeAccounts = paymentController.listAccounts(TEST_CUSTOMER_ID);
        assertFalse(beforeAccounts.isEmpty(), "Customer 2 should have at least one account");
        AccountSummaryDto accBefore = beforeAccounts.get(0);

        NewPaymentRequest req = new NewPaymentRequest(
                TEST_CUSTOMER_ID,
                accBefore.id(),
                "CZ0201000000000012345678",
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
