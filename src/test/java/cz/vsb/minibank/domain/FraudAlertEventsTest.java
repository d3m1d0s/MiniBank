package cz.vsb.minibank.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Observer infrastructure around FraudAlertEvents.
 */
class FraudAlertEventsTest {

    private static class RecordingObserver implements FraudAlertObserver {
        FraudAlert lastAlert;
        FraudAlertState lastOld;
        FraudAlertState lastNew;
        int callCount = 0;

        @Override
        public void onStateChanged(FraudAlert alert,
                                   FraudAlertState oldState,
                                   FraudAlertState newState) {
            this.lastAlert = alert;
            this.lastOld = oldState;
            this.lastNew = newState;
            this.callCount++;
        }
    }

    @BeforeEach
    void resetObservers() {
        FraudAlertEvents.clearObservers();
    }

    @Test
    void notifyStateChanged_notifiesAllRegisteredObservers() {
        RecordingObserver o1 = new RecordingObserver();
        RecordingObserver o2 = new RecordingObserver();

        FraudAlertEvents.register(o1);
        FraudAlertEvents.register(o2);

        FraudAlert dummy = null;

        FraudAlertEvents.notifyStateChanged(dummy,
                FraudAlertState.NEW,
                FraudAlertState.SUSPICIOUS);

        assertEquals(1, o1.callCount);
        assertEquals(1, o2.callCount);
        assertSame(dummy, o1.lastAlert);
        assertEquals(FraudAlertState.NEW, o1.lastOld);
        assertEquals(FraudAlertState.SUSPICIOUS, o1.lastNew);
    }

    @Test
    void clearObservers_removesAllObservers() {
        RecordingObserver o1 = new RecordingObserver();
        FraudAlertEvents.register(o1);

        FraudAlertEvents.clearObservers();

        FraudAlertEvents.notifyStateChanged(null,
                FraudAlertState.NEW,
                FraudAlertState.OK);

        assertEquals(0, o1.callCount,
                "After clearObservers() the observer must not be called");
    }
}
