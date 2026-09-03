package cz.vsb.minibank.application.payment;

import cz.vsb.minibank.domain.transfer.Transfer;
import cz.vsb.minibank.domain.value.Money;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FakePaymentNetworkGatewayTest {

    private static final String TARGET_IBAN = "CZ0401000000000000000000";

    @Test
    void sendStoresTransferInMemory() {
        FakePaymentNetworkGateway gateway = new FakePaymentNetworkGateway();
        Transfer t = new Transfer(
                1,
                10,
                null,
                TARGET_IBAN,
                Money.czk(1_000));

        gateway.send(t);

        assertEquals(1, gateway.sentTransfers().size());
        assertSame(t, gateway.sentTransfers().get(0));
    }

    @Test
    void clearRemovesAllStoredTransfers() {
        FakePaymentNetworkGateway gateway = new FakePaymentNetworkGateway();
        Transfer t = new Transfer(
                1,
                10,
                null,
                TARGET_IBAN,
                Money.czk(500));
        gateway.send(t);
        assertFalse(gateway.sentTransfers().isEmpty());

        gateway.clear();

        assertTrue(gateway.sentTransfers().isEmpty());
    }

    // ------------------------------------------------------------ idempotency

    /**
     * The gateway interface asks an implementation to be idempotent for the same transfer id, and
     * this one appended whatever it was handed. Dispatch is at-least-once by design:
     * PaymentDispatcher sends first and marks the row afterwards, so a crash or a refused mark
     * leaves it PENDING and the next sweep offers the same payment again, and a history that
     * appended blindly would then claim the money left twice.
     */
    @Test
    void theSameTransferSentTwiceIsRecordedOnce() {
        FakePaymentNetworkGateway gateway = new FakePaymentNetworkGateway();
        Transfer t = new Transfer(7, 10, null, TARGET_IBAN, Money.czk(1_000));

        gateway.send(t);
        gateway.send(t);

        assertEquals(1, gateway.sentTransfers().size());
        assertSame(t, gateway.sentTransfers().get(0));
    }

    /**
     * The key is the id rather than the instance, which is what makes it useful: the retry the
     * paragraph above describes reloads the row, so the same payment comes back as a different
     * object.
     */
    @Test
    void aReloadedTransferWithTheSameIdIsRecordedOnce() {
        FakePaymentNetworkGateway gateway = new FakePaymentNetworkGateway();
        Transfer first = new Transfer(7, 10, null, TARGET_IBAN, Money.czk(1_000));
        Transfer reloaded = new Transfer(7, 10, null, TARGET_IBAN, Money.czk(1_000));

        gateway.send(first);
        gateway.send(reloaded);

        assertEquals(1, gateway.sentTransfers().size());
        assertSame(first, gateway.sentTransfers().get(0));
    }

    /**
     * Clearing has to reset the dispatched ids as well as the history. A test that clears between
     * cases and then sends its fixture again would otherwise watch the gateway record nothing at
     * all.
     */
    @Test
    void clearLetsAPreviouslySentTransferBeRecordedAgain() {
        FakePaymentNetworkGateway gateway = new FakePaymentNetworkGateway();
        Transfer t = new Transfer(7, 10, null, TARGET_IBAN, Money.czk(1_000));
        gateway.send(t);

        gateway.clear();
        gateway.send(t);

        assertEquals(1, gateway.sentTransfers().size());
        assertSame(t, gateway.sentTransfers().get(0));
    }

    // ------------------------------------------------------------ concurrency

    /**
     * Under the REST API this stub is the gateway, and PaymentDispatcher enters it from whichever
     * request threads have just committed a settling payment. An unsynchronized list loses appends
     * between them and can throw out of the copy that grows it, and a lost append falsifies the
     * history the tests read. Eight threads released together dispatch two hundred distinct
     * payments each, and the history has to hold every one of them.
     *
     * Nothing is asserted about the order they landed in. The threads interleave, and membership
     * is the only thing this stub promises across them.
     */
    @Test
    void everyTransferDispatchedConcurrentlyIsRecorded() throws Exception {
        FakePaymentNetworkGateway gateway = new FakePaymentNetworkGateway();

        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> running = new ArrayList<>();

        try {
            for (int thread = 0; thread < threads; thread++) {
                int firstId = thread * perThread;
                running.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        gateway.send(new Transfer(firstId + i, 10, null,
                                TARGET_IBAN, Money.czk(100)));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : running) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<Transfer> sent = gateway.sentTransfers();
        assertEquals(threads * perThread, sent.size(),
                "no append may be lost when threads dispatch at the same moment");

        Set<Integer> ids = new HashSet<>();
        for (Transfer t : sent) {
            ids.add(t.id());
        }
        assertEquals(threads * perThread, ids.size(),
                "every payment dispatched must be in the history exactly once");
    }
}
