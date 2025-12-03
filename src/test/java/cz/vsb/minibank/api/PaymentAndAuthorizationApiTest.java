package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.*;
import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PaymentAndAuthorizationApiTest {

    private PaymentController paymentController;
    private AuthorizationController authorizationController;
    private AccountRepository accounts;
    private TransferRepository transfers;

    @BeforeEach
    void setup() {
        Bootstrap infra = new Bootstrap("data/data.json");

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


    /**
     * Утилита: создать новый платёж клиента 2, который попадёт в WAITING_AUTH,
     * и вернуть его id.
     */
    private int createWaitingTransferForCustomer2() {
        // берём первый счёт клиента 2 (в твоём data.json это 101)
        List<AccountSummaryDto> accList = paymentController.listAccounts(2);
        assertFalse(accList.isEmpty(), "Customer 2 should have at least one account");
        AccountSummaryDto acc = accList.get(0);

        NewPaymentRequest req = new NewPaymentRequest(
                2,
                acc.id(),
                "CZ0201000000000012345678",
                1000.0,
                "Test waiting transfer"
        );

        NewPaymentResultDto result = paymentController.createPayment(req).getBody();
        assertNotNull(result, "Result body must not be null");

        int transferId = result.transferId();
        Transfer t = transfers.byId(transferId)
                .orElseThrow(() -> new AssertionError("New transfer not found in repository"));

        // По бизнес-правилам (см. твой RuleBasedRiskService) для 1000 CZK
        // и лимита 5000 перевод уходит в WAITING_AUTH.
        assertEquals(
                TransferStatus.WAITING_AUTH,
                t.status(),
                "Precondition: newly created transfer must be in WAITING_AUTH"
        );

        return transferId;
    }

    @Test
    void listAccounts_forExistingCustomer2_returnsAccount101() {
        List<AccountSummaryDto> list = paymentController.listAccounts(2);
        assertFalse(list.isEmpty(), "Expected accounts for customer 2");
        AccountSummaryDto acc = list.get(0);
        assertEquals(101, acc.id());
        assertTrue(acc.iban().startsWith("CZ"));
    }

    @Test
    void listWaitingTransfers_forCustomer2_containsNewWaitingTransfer() {
        int transferId = createWaitingTransferForCustomer2();

        List<WaitingTransferItemDto> waiting = authorizationController.listWaiting(2);
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
        assertTrue(details.amount().startsWith("1000.00"), "Expected amount to start with 1000.00");
        assertEquals("WAITING_AUTH", details.status());
        assertNotNull(details.fromIban());
        assertTrue(details.fromIban().startsWith("CZ"));
    }

    @Test
    void authorizePayment_withInvalidOtp_declinesNewTransfer() {
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

        assertEquals(TransferStatus.DECLINED.name(), result.status());
        assertEquals(TransferStatus.DECLINED, after.status());
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
        List<AccountSummaryDto> beforeAccounts = paymentController.listAccounts(2);
        assertFalse(beforeAccounts.isEmpty(), "Customer 2 should have at least one account");
        AccountSummaryDto accBefore = beforeAccounts.get(0);

        NewPaymentRequest req = new NewPaymentRequest(
                2,
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
