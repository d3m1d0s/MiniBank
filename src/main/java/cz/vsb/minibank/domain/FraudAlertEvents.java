package cz.vsb.minibank.domain;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple domain event publisher for fraud alert related events.
 *
 * Implements the subject part of the Observer pattern.
 */
public final class FraudAlertEvents {

    private static final List<FraudAlertObserver> observers = new CopyOnWriteArrayList<>();

    private FraudAlertEvents() {
    }

    /**
     * Registers a new fraud alert observer.
     */
    public static void register(FraudAlertObserver observer) {
        if (observer != null) {
            observers.add(observer);
        }
    }

    /**
     * Removes all registered fraud alert observers.
     */
    public static void clearObservers() {
        observers.clear();
    }

    /**
     * Notifies observers about a fraud alert state change.
     */
    public static void notifyStateChanged(FraudAlert alert,
                                          FraudAlertState oldState,
                                          FraudAlertState newState) {
        for (FraudAlertObserver o : observers) {
            o.onStateChanged(alert, oldState, newState);
        }
    }
}
