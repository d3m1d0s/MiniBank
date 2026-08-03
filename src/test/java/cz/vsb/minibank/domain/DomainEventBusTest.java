package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.value.Money;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The subject half of the Observer pattern, on its own.
 *
 * Replaces the two tests of the static buses this class removed. They needed a clearObservers()
 * in a @BeforeEach so that one test could not change what a later one saw; a bus that is created
 * per test needs nothing, and that is the point of the change rather than an accident of it.
 */
class DomainEventBusTest {

    private static final class RecordingTransferObserver implements TransferObserver {
        Transfer lastTransfer;
        TransferStatus lastOld;
        TransferStatus lastNew;
        int callCount;

        @Override
        public void onStatusChanged(Transfer transfer, TransferStatus oldStatus, TransferStatus newStatus) {
            this.lastTransfer = transfer;
            this.lastOld = oldStatus;
            this.lastNew = newStatus;
            this.callCount++;
        }
    }

    private static final class RecordingAlertObserver implements FraudAlertObserver {
        FraudAlertState lastOld;
        FraudAlertState lastNew;
        int callCount;

        @Override
        public void onStateChanged(FraudAlert alert, FraudAlertState oldState, FraudAlertState newState) {
            this.lastOld = oldState;
            this.lastNew = newState;
            this.callCount++;
        }
    }

    @Test
    void everyObserverOfThatKindHearsTheEventExactlyOnce() {
        DomainEventBus bus = new DomainEventBus();
        RecordingTransferObserver first = new RecordingTransferObserver();
        RecordingTransferObserver second = new RecordingTransferObserver();
        bus.register(first);
        bus.register(second);

        Transfer transfer = aTransfer();
        bus.publish(new TransferStatusChanged(transfer, TransferStatus.CREATED, TransferStatus.WAITING_AUTH));

        assertEquals(1, first.callCount);
        assertEquals(1, second.callCount);
        assertSame(transfer, first.lastTransfer, "the observer must be handed the aggregate itself");
        assertEquals(TransferStatus.CREATED, first.lastOld);
        assertEquals(TransferStatus.WAITING_AUTH, first.lastNew);
    }

    @Test
    void anEventReachesOnlyTheObserversOfItsOwnKind() {
        DomainEventBus bus = new DomainEventBus();
        RecordingTransferObserver transfers = new RecordingTransferObserver();
        RecordingAlertObserver alerts = new RecordingAlertObserver();
        bus.register(transfers);
        bus.register(alerts);

        bus.publish(new FraudAlertStateChanged(
                new FraudAlert(1, 2, "reason"), FraudAlertState.NEW, FraudAlertState.SUSPICIOUS));

        assertEquals(0, transfers.callCount, "a transfer observer must not hear about an alert");
        assertEquals(1, alerts.callCount);
        assertEquals(FraudAlertState.NEW, alerts.lastOld);
        assertEquals(FraudAlertState.SUSPICIOUS, alerts.lastNew);
    }

    @Test
    void aWholeTransactionsEventsAreDeliveredInTheOrderGiven() {
        DomainEventBus bus = new DomainEventBus();
        StringBuilder seen = new StringBuilder();
        bus.register((TransferObserver) (t, from, to) -> seen.append(to).append(' '));

        Transfer transfer = aTransfer();
        bus.publishAll(List.of(
                new TransferStatusChanged(transfer, TransferStatus.CREATED, TransferStatus.WAITING_AUTH),
                new TransferStatusChanged(transfer, TransferStatus.WAITING_AUTH, TransferStatus.SENT)));

        assertEquals("WAITING_AUTH SENT ", seen.toString());
    }

    @Test
    void anEventNobodyIsListeningForIsDroppedRatherThanRefused() {
        DomainEventBus bus = new DomainEventBus();

        // No observers at all, and an event of a kind this bus does not know. Both must be
        // silent: publishing runs after the transaction has committed, so a throw here would
        // report a failure for work that has already succeeded.
        bus.publish(new DomainEvent() { });
        bus.publishAll(List.of(new DomainEvent() { }));

        assertEquals(0, bus.observerCount());
    }

    private static Transfer aTransfer() {
        return new Transfer(1, 1, null, "CZ6508000000192000145399", Money.czk(100), "CZK");
    }
}
