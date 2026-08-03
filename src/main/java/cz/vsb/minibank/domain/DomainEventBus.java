package cz.vsb.minibank.domain;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The subject half of the Observer pattern: who hears about domain events.
 *
 * An instance, not a static holder. The two static buses this replaces were the reason a fresh
 * BootstrapServices could pile another observer onto a list that outlived it, the reason the
 * tests needed a clearObservers() at all, and the reason one test could change what a later test
 * in the same fork observed. A bus that is created by {@code Bootstrap} and handed to the unit of
 * work factory has none of that: it lives exactly as long as the infrastructure that owns it.
 *
 * Registration is expected once per process, at whichever composition root starts it. The list is
 * copy-on-write so that publishing from one thread while another registers cannot fail, not
 * because registration is expected to be concurrent.
 */
public final class DomainEventBus {

    private final List<TransferObserver> transferObservers = new CopyOnWriteArrayList<>();
    private final List<FraudAlertObserver> alertObservers = new CopyOnWriteArrayList<>();

    public void register(TransferObserver observer) {
        if (observer != null) {
            transferObservers.add(observer);
        }
    }

    public void register(FraudAlertObserver observer) {
        if (observer != null) {
            alertObservers.add(observer);
        }
    }

    /** How many observers are attached, for tests that assert the wiring. */
    public int observerCount() {
        return transferObservers.size() + alertObservers.size();
    }

    /**
     * Delivers one event to whoever is listening for its kind.
     *
     * An unknown event type is delivered to nobody rather than refused. Adding an event that
     * nothing observes yet is a normal step, and a throw here would turn it into a failed
     * transaction - after the transaction has already committed, which is the worst place to
     * discover it.
     */
    public void publish(DomainEvent event) {
        if (event instanceof TransferStatusChanged e) {
            for (TransferObserver o : transferObservers) {
                o.onStatusChanged(e.transfer(), e.oldStatus(), e.newStatus());
            }
        } else if (event instanceof FraudAlertStateChanged e) {
            for (FraudAlertObserver o : alertObservers) {
                o.onStateChanged(e.alert(), e.oldState(), e.newState());
            }
        }
    }

    /**
     * Delivers a whole transaction's events, in the order given.
     *
     * The order is the one the unit of work collected them in: within a single aggregate it is
     * the order the changes happened, across aggregates it is whatever order the identity map
     * yields. A transfer and the fraud alert raised against it can therefore reach the audit log
     * either way round. Left as it is rather than given a sequence number, because the log line
     * carries its own timestamp and nothing in this project reads the audit trail back.
     */
    public void publishAll(List<DomainEvent> events) {
        for (DomainEvent event : events) {
            publish(event);
        }
    }
}
