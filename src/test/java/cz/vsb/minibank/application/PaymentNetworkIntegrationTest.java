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
        // Temporary JSON store, similar to other tests
        tempDir = Files.createTempDirectory("minibank-payment-");
        dataPath = tempDir.resolve("data.json").toString();
        infra = new Bootstrap(dataPath);

        // Build TransferApplicationService with test dependencies

        // Zero fee policy for deterministic balances
        FeePolicy feePolicy = new ZeroFeePolicy();

        // Real risk service is acceptable here
        RiskService riskService = new RuleBasedRiskService();

        // OTP always valid for this integration test
        OtpValidator otpValidator = (id, otp) -> true;

        // In-memory gateway to simulate payment network
        gateway = new FakePaymentNetworkGateway();

        transferService = new TransferApplicationService(
                infra.accounts,
                infra.transfers,
                infra.alerts,
                feePolicy,
                riskService,
                otpValidator,
                gateway,
                infra.uowFactory,
                new OwnershipGuard(infra.customers, infra.accounts)
        );

        // Initial domain data (outside any UnitOfWork)

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

        // Prepare initial state for UC 05: transfer in WAITING_AUTH

        transferId = infra.transfers.nextId();
        Transfer t = new Transfer(
                transferId,
                accountId,
                null,
                "CZ1301000000000098765432",
                Money.czk(1_000),
                "CZK"
        );
        // Simulate payment already created and pending authorization
        t.requestAuthorization(new CardPayment(t.amount(), "****0000"));
        infra.transfers.add(t);

        a.registerTransfer(transferId);
        infra.accounts.save(a);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted((p1, p2) -> p2.compareTo(p1)) // delete files first, then directory
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
        // The fixture links accountId to customerId and saves the customer afterwards, so the
        // ownership guard passes on the data this test already sets up.
        transferService.authorizePayment(customerId, transferId, "any-otp");

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
