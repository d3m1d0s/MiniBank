package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PaymentNetworkIntegrationTest {

    Path tempDir;
    String dataPath;
    Bootstrap infra;

    TransferApplicationService transferService;
    FakePaymentNetworkGateway gateway;

    int customerId;
    int accountId;
    int transferId;

    @BeforeEach
    void setUp() throws IOException {
        // Temporary JSON store, same as in other tests
        tempDir = Files.createTempDirectory("minibank-payment-");
        dataPath = tempDir.resolve("data.json").toString();
        infra = new Bootstrap(dataPath);

        // --- Build TransferApplicationService with test dependencies ---

        // Special Case: zero fee (can be replaced with SimpleFeePolicy if ZeroFeePolicy does not exist yet)
        FeePolicy feePolicy = new ZeroFeePolicy();

        // RiskService is not used here (we only test UC 05), so we can keep the real implementation
        RiskService riskService = new RuleBasedRiskService();

        // Stub: OTP is always valid -> no need to guess the "correct" code
        OtpValidator otpValidator = (id, otp) -> true;

        // Stub: our test gateway to the payment network
        gateway = new FakePaymentNetworkGateway();

        transferService = new TransferApplicationService(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                feePolicy,
                riskService,
                otpValidator,
                gateway,
                infra.uowFactory
        );

        // --- Initial domain data (outside UoW, same as in other tests) ---

        customerId = infra.customers.nextId();
        Customer c = new Customer(
                customerId,
                "Integration User",
                "integration@example.com",
                new Address("Street 1", "Test City")
        );
        infra.customers.save(c);

        accountId = infra.accounts.nextId();
        Account a = new Account(
                accountId,
                new IBAN("CZ6508000000192000145399"),
                Money.czk(20_000),
                Money.czk(10_000)
        );
        infra.accounts.save(a);
        c.addAccountId(accountId);
        infra.customers.save(c);

        // --- Prepare initial state for UC 05: WAITING_AUTH ---

        transferId = infra.transfers.nextId();
        Transfer t = new Transfer(
                transferId,
                accountId,
                null,
                "CZ0201000000000098765432",
                Money.czk(1_000),
                "CZK"
        );
        // Simulate that the payment has already been created and sent for authorization
        t.requestAuthorization(new CardPayment(t.amount(), "****0000"));
        infra.transfers.add(t);

        a.registerTransfer(transferId);
        infra.accounts.save(a);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted((p1, p2) -> p2.compareTo(p1)) // files first, then directory
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    void authorizedTransferIsDispatchedToPaymentNetwork() {
        // act: UC 05 – successful payment authorization
        transferService.authorizePayment(transferId, "any-otp");

        // assert - gateway is called exactly once with our transfer
        assertEquals(1, gateway.sentTransfers().size(),
                "Payment network gateway should be called exactly once");
        Transfer sent = gateway.sentTransfers().get(0);
        assertEquals(transferId, sent.id());

        // assert - transfer status in repository is updated to SENT
        TransferRepository transfers = infra.transfers;
        Transfer fromRepo = transfers.byId(transferId).orElseThrow();

        assertEquals(TransferStatus.SENT, fromRepo.status(),
                "Transfer must be marked as SENT after dispatch to payment network");
    }
}
