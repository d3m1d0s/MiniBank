package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.exceptions.TransferChangedException;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When a payment reaches the network, and - the part this class exists for - when it must not.
 *
 * The dispatch used to happen inside the unit of work that settled the payment, before the commit
 * that writes the debit. A commit that failed afterwards therefore rolled the debit back over a
 * payment that had already gone, and told the customer nothing had been charged and to send it
 * again. Every test here is about the seam that closes it: the network is reached from
 * {@link PaymentDispatcher}, on the bus, after a commit that succeeded.
 *
 * The dispatcher is attached per test rather than in the fixture, because one of these cases is
 * exactly the process that settled a payment and never dispatched it.
 */
class PaymentNetworkIntegrationTest {

    private static final Money OPENING_BALANCE = Money.czk(20_000);
    private static final Money AMOUNT = Money.czk(1_000);

    /** Held by no account of this store, so the payment leaves the bank and owes the network. */
    private static final String OUTSIDE_IBAN = "CZ1301000000000098765432";

    Path tempDir;
    String dataPath;
    Bootstrap infra;

    TransferApplicationService transferService;
    FakePaymentNetworkGateway gateway;
    PaymentDispatcher dispatcher;

    int customerId;
    int accountId;
    int transferId;

    @BeforeEach
    void setUp() throws IOException {
        // Temporary JSON store, similar to other tests
        tempDir = Files.createTempDirectory("minibank-payment-");
        dataPath = tempDir.resolve("data.json").toString();
        infra = new Bootstrap(dataPath);

        // In-memory gateway to simulate payment network. No service holds it any more: the only
        // thing that calls it is the dispatcher, which the tests below attach when they mean to.
        gateway = new FakePaymentNetworkGateway();
        dispatcher = new PaymentDispatcher(gateway, infra.transfers, infra.uowFactory);

        transferService = serviceOver(infra.uowFactory);

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
                OPENING_BALANCE,
                Money.czk(10_000)
        );
        infra.accounts.save(a);
        c.addAccountId(accountId);
        infra.customers.save(c);

        // Prepare initial state for UC 05: transfer in WAITING_AUTH

        transferId = infra.transfers.nextId();
        Transfer t = new Transfer(transferId, accountId, null, OUTSIDE_IBAN, AMOUNT);
        // Simulate payment already created and pending authorization
        t.requestAuthorization(new CardPayment(t.amount(), "****0000"));
        infra.transfers.add(t);

        // Account.registerTransfer and the second save that persisted its list are gone with
        // Account.transferIds. Nothing here asserted anything about that list; the account was
        // already saved above, and the transfer's own source_account_id is what every real
        // "transfers of this account" query reads.
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

    /**
     * The same service the fixture builds, over whichever unit of work factory the case needs.
     *
     * It takes no gateway. That is the shape of the change: the application service records what a
     * settled payment owes the network and knows nothing about how it is paid.
     */
    private TransferApplicationService serviceOver(UnitOfWorkFactory factory) {
        return new TransferApplicationService(
                infra.accounts,
                infra.transfers,
                infra.alerts,
                new ZeroFeePolicy(),          // deterministic balances
                new RuleBasedRiskService(),   // the real risk service is acceptable here
                (id, otp) -> true,            // OTP always valid for this integration test
                factory,
                new OwnershipGuard(infra.customers, infra.accounts)
        );
    }

    /**
     * A factory whose transactions run normally and then cannot commit.
     *
     * The poison is a mutation registered the moment the unit of work opens, so it fails where a
     * real one fails - inside the commit's mutation loop, after the use case has done all of its
     * work in memory - and it fails with one of the exceptions that really occurs there, the
     * version guard on the transfers row refusing a write built on a stale read. Both units of
     * work answer that the way they answer any failed commit: nothing is persisted, and on the
     * JSON store the mutations already applied to the shared bundle are reverted.
     */
    private UnitOfWorkFactory commitAlwaysFails() {
        return () -> {
            UnitOfWork uow = infra.uowFactory.begin();
            uow.registerMutation(() -> {
                throw new TransferChangedException(
                        "forced: transfer " + transferId + " was changed by another transaction");
            });
            return uow;
        };
    }

    @Test
    void authorizedTransferIsDispatchedToPaymentNetwork() {
        infra.events.register(dispatcher);

        // act: UC 05 - successful payment authorization
        // The fixture links accountId to customerId and saves the customer afterwards, so the
        // ownership guard passes on the data this test already sets up.
        transferService.authorizePayment(customerId, transferId, "any-otp");

        // assert - gateway is called exactly once with our transfer
        assertEquals(1, gateway.sentTransfers().size(),
                "Payment network gateway should be called exactly once");
        assertEquals(transferId, gateway.sentTransfers().get(0).id());

        // assert - the stored row records both halves: the money moved, and the network has it
        Transfer fromRepo = infra.transfers.byId(transferId).orElseThrow();
        assertEquals(TransferStatus.SENT, fromRepo.status(),
                "Transfer must be marked as SENT after dispatch to payment network");
        assertEquals(DispatchState.DISPATCHED, fromRepo.dispatchState(),
                "the dispatch is recorded after it happened, so a restart does not repeat it");
    }

    /**
     * The phantom dispatch, and the reason the whole design exists.
     *
     * The commit fails after settle has run, which is the window that used to be open: the debit
     * and the SENT status are rolled back, so the customer is answered that nothing was charged
     * and invited to send the payment again. If the network had already been handed the payment at
     * that point, accepting that invitation would dispatch the same money twice.
     *
     * The dispatcher is on the bus here, so this is not passing because nothing was listening.
     * Both units of work publish only after a commit that succeeded, and this commit does not.
     */
    @Test
    void aSettlementWhoseCommitFailsIsNeverHandedToTheNetwork() {
        infra.events.register(dispatcher);
        TransferApplicationService overAFailingCommit = serviceOver(commitAlwaysFails());

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> overAFailingCommit.authorizePayment(customerId, transferId, "any-otp"));

        // The planted failure and not some other one. Everything asserted below is also true of a
        // use case that broke before it ever reached settle, so without this the case could pass
        // while never opening the window it exists to close. JsonUnitOfWork.commit wraps what a
        // mutation throws, which is why this looks at the cause.
        assertInstanceOf(TransferChangedException.class, failure.getCause(),
                "the transaction must have failed in its commit, where a real one fails");

        assertTrue(gateway.sentTransfers().isEmpty(),
                "a payment whose transaction was rolled back has not left this bank, and telling"
                        + " the network otherwise is the double spend the 409 body invites");

        Transfer fromRepo = infra.transfers.byId(transferId).orElseThrow();
        assertEquals(TransferStatus.WAITING_AUTH, fromRepo.status(),
                "the transfer is exactly as it was found");
        assertNull(fromRepo.dispatchState(),
                "and it owes the network nothing, so no later sweep will send it either");
        assertEquals(OPENING_BALANCE, infra.accounts.byId(accountId).orElseThrow().balance(),
                "nothing was charged, which is what the 409 tells the customer");
    }

    /**
     * The other half of at-least-once: a payment that committed and was never handed over.
     *
     * No dispatcher is attached, which is this process settling the payment and dying before it
     * dispatched anything. The row is left saying the money is gone and nobody has been told, and
     * the sweep is what tells them.
     */
    @Test
    void aPaymentLeftPendingByACrashIsDispatchedByTheNextSweep() {
        transferService.authorizePayment(customerId, transferId, "any-otp");

        assertTrue(gateway.sentTransfers().isEmpty(),
                "settling no longer dispatches, so nothing has been handed over yet");
        assertEquals(DispatchState.PENDING,
                infra.transfers.byId(transferId).orElseThrow().dispatchState(),
                "the committed row is what remembers the obligation");

        dispatcher.sweepPending();

        assertEquals(1, gateway.sentTransfers().size(), "the sweep hands it over");
        assertEquals(transferId, gateway.sentTransfers().get(0).id());
        assertEquals(DispatchState.DISPATCHED,
                infra.transfers.byId(transferId).orElseThrow().dispatchState());

        dispatcher.sweepPending();

        assertEquals(1, gateway.sentTransfers().size(),
                "and a payment already handed over is not offered a second time");
    }
}
