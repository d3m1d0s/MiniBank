package cz.vsb.minibank.domain;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple domain event publisher for FraudAlert-related events.
 *
 * Implements the "subject" part of the Observer pattern.
 */
public final class FraudAlertEvents {

    private static final List<FraudAlertObserver> observers = new CopyOnWriteArrayList<>();

    private FraudAlertEvents() {
    }

    public static void register(FraudAlertObserver observer) {
        if (observer != null) {
            observers.add(observer);
        }
    }

    public static void clearObservers() {
        observers.clear();
    }

    public static void notifyStateChanged(FraudAlert alert,
                                          FraudAlertState oldState,
                                          FraudAlertState newState) {
        for (FraudAlertObserver o : observers) {
            o.onStateChanged(alert, oldState, newState);
        }
    }
}
