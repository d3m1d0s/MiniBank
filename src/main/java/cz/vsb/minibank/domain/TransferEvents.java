package cz.vsb.minibank.domain;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple domain event publisher for transfer related events.
 *
 * Implements the subject part of the Observer pattern.
 */
public final class TransferEvents {

    private static final List<TransferObserver> observers = new CopyOnWriteArrayList<>();

    private TransferEvents() {}

    /**
     * Registers a new transfer observer.
     */
    public static void register(TransferObserver observer) {
        if (observer != null) {
            observers.add(observer);
        }
    }

    /**
     * Removes all registered transfer observers.
     */
    public static void clearObservers() {
        observers.clear();
    }

    /**
     * How many observers are attached.
     *
     * The bus is static and process-wide, so "attached twice" is a real defect rather than a
     * detail: it duplicates every audit line and it is invisible to any assertion about what a
     * single observer saw. Exposed so that the composition roots can be held to attaching the
     * audit observers exactly once.
     */
    public static int observerCount() {
        return observers.size();
    }

    /**
     * Notifies observers about a transfer status change.
     */
    public static void notifyStatusChanged(Transfer transfer,
                                           TransferStatus oldStatus,
                                           TransferStatus newStatus) {
        for (TransferObserver o : observers) {
            o.onStatusChanged(transfer, oldStatus, newStatus);
        }
    }
}
