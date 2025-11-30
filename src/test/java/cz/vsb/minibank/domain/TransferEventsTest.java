package cz.vsb.minibank.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Observer infrastructure around TransferEvents.
 */
class TransferEventsTest {

    /**
     * Simple test observer that records the last notification.
     */
    private static class RecordingObserver implements TransferObserver {
        Transfer lastTransfer;
        TransferStatus lastOldStatus;
        TransferStatus lastNewStatus;
        int callCount = 0;

        @Override
        public void onStatusChanged(Transfer transfer,
                                    TransferStatus oldStatus,
                                    TransferStatus newStatus) {
            this.lastTransfer = transfer;
            this.lastOldStatus = oldStatus;
            this.lastNewStatus = newStatus;
            this.callCount++;
        }
    }

    @BeforeEach
    void resetObservers() {
        // important so that tests do not affect each other
        TransferEvents.clearObservers();
    }

    @Test
    void notifyStatusChanged_notifiesAllRegisteredObservers() {
        RecordingObserver o1 = new RecordingObserver();
        RecordingObserver o2 = new RecordingObserver();

        TransferEvents.register(o1);
        TransferEvents.register(o2);

        // for testing the mechanism we do not care about the actual Transfer instance → can pass null
        Transfer dummy = null;

        TransferEvents.notifyStatusChanged(dummy,
                TransferStatus.CREATED,
                TransferStatus.WAITING_AUTH);

        // both observers should be called exactly once
        assertEquals(1, o1.callCount);
        assertEquals(1, o2.callCount);

        // parameters must match the ones passed
        assertSame(dummy, o1.lastTransfer);
        assertSame(dummy, o2.lastTransfer);
        assertEquals(TransferStatus.CREATED, o1.lastOldStatus);
        assertEquals(TransferStatus.WAITING_AUTH, o1.lastNewStatus);
    }

    @Test
    void clearObservers_removesAllObservers() {
        RecordingObserver o1 = new RecordingObserver();
        TransferEvents.register(o1);

        // clear subscribers
        TransferEvents.clearObservers();

        // notify again - listener must not be called
        TransferEvents.notifyStatusChanged(null,
                TransferStatus.CREATED,
                TransferStatus.SENT);

        assertEquals(0, o1.callCount,
                "After clearObservers() the listener must not be called");
    }
}
