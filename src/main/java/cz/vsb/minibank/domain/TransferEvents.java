package cz.vsb.minibank.domain;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple domain event publisher for Transfer-related events.
 *
 * Implements the "subject" part of the Observer pattern.
 */
public final class TransferEvents {

    private static final List<TransferObserver> observers = new CopyOnWriteArrayList<>();

    private TransferEvents() {}

    public static void register(TransferObserver observer) {
        if (observer != null) {
            observers.add(observer);
        }
    }

    public static void clearObservers() {
        observers.clear();
    }

    public static void notifyStatusChanged(Transfer transfer,
                                           TransferStatus oldStatus,
                                           TransferStatus newStatus) {
        for (TransferObserver o : observers) {
            o.onStatusChanged(transfer, oldStatus, newStatus);
        }
    }
}
