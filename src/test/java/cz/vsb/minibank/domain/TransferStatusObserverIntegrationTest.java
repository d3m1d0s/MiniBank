package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.value.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-like test that checks that Transfer methods
 * actually trigger status change events.
 */
class TransferStatusObserverIntegrationTest {

    private static class RecordingObserver implements TransferObserver {
        TransferStatus lastOld;
        TransferStatus lastNew;
        int callCount = 0;

        @Override
        public void onStatusChanged(Transfer transfer,
                                    TransferStatus oldStatus,
                                    TransferStatus newStatus) {
            this.lastOld = oldStatus;
            this.lastNew = newStatus;
            this.callCount++;
        }
    }

    @BeforeEach
    void resetObservers() {
        TransferEvents.clearObservers();
    }

    @Test
    void requestAuthorization_firesStatusChangeEvent() {
        RecordingObserver observer = new RecordingObserver();
        TransferEvents.register(observer);

        Transfer t = createDummyTransferCreated();

        // method in which we call notifyStatusChanged(...)
        t.requestAuthorization(null);

        assertEquals(1, observer.callCount);
        assertEquals(TransferStatus.CREATED, observer.lastOld);
        assertEquals(TransferStatus.WAITING_AUTH, observer.lastNew);
    }

    @Test
    void decline_firesStatusChangeEvent() {
        RecordingObserver observer = new RecordingObserver();
        TransferEvents.register(observer);

        Transfer t = createDummyTransferCreated();

        t.decline("test decline");

        assertEquals(1, observer.callCount);
        assertEquals(TransferStatus.CREATED, observer.lastOld);
        assertEquals(TransferStatus.DECLINED, observer.lastNew);
    }

    /**
     * Builds a Transfer in CREATED status with a minimal set of valid arguments.
     */
    private Transfer createDummyTransferCreated() {
        int id = 1;
        int sourceAccountId = 1;          // any account ID, not used in these tests
        Integer beneficiaryId = null;     // null is fine because we do not access beneficiary()
        String targetIbanSnapshot = "CZ6508000000192000145399"; // any valid-looking value
        Money amount = Money.czk(100);
        String currency = "CZK";

        return new Transfer(
                id,
                sourceAccountId,
                beneficiaryId,
                targetIbanSnapshot,
                amount,
                currency
        );
    }
}
