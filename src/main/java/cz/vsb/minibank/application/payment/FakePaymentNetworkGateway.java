package cz.vsb.minibank.application.payment;

import cz.vsb.minibank.domain.transfer.Transfer;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple in memory stub of PaymentNetworkGateway used for development and tests.
 *
 * Stub describes what it models, not where it runs. BootstrapServices builds one for every wiring
 * that does not supply a gateway of its own, and the API configuration hands that same instance
 * out as the PaymentNetworkGateway bean, so under the REST API its send() is entered by as many
 * request threads as happen to be settling payments at that moment. The plain ArrayList this used
 * to keep was therefore not a test-only shortcut. Two unsynchronized appends can lose one of the
 * two or fail inside the array copy that grows the list, and PaymentDispatcher enters send() from
 * whichever thread has just committed a settling payment - after the commit and outside any unit
 * of work, on both backends - so two payments settling together reach this list at the same
 * moment.
 *
 * The list is copy-on-write for the reason DomainEventBus keeps its observers that way. Writes
 * are one small append per settled payment and reads are whole traversals by a test or a demo,
 * which is the shape copying on write is cheap for.
 *
 * Dispatch is idempotent, which {@link PaymentNetworkGateway} asks of an implementation and this
 * one did not offer. Dispatch is at-least-once: a payment whose mark did not stick is offered
 * again by the next sweep, rebuilt from its row as a different object, and a history that
 * appended blindly would then claim the money left the bank twice.
 */
public final class FakePaymentNetworkGateway implements PaymentNetworkGateway {

    private final List<Transfer> sentTransfers = new CopyOnWriteArrayList<>();

    /**
     * What has already been dispatched. Held beside the list rather than derived from it, so the
     * duplicate check is one atomic operation instead of a scan and an append that a second
     * thread can slip between.
     */
    private final Set<Integer> dispatchedIds = ConcurrentHashMap.newKeySet();

    @Override
    public void send(Transfer transfer) {
        // The idempotency key is the transfer id and not the instance: the sweep rebuilds a
        // still-PENDING payment from its row, so the same payment arrives here as a different
        // object.
        if (dispatchedIds.add(transfer.id())) {
            sentTransfers.add(transfer);
        }
    }

    /**
     * Returns an unmodifiable view of the sent transfers for tests or demos.
     */
    public List<Transfer> sentTransfers() {
        return Collections.unmodifiableList(sentTransfers);
    }

    /**
     * Clears the in memory history of sent transfers.
     *
     * The dispatched ids go with it. A gateway that forgot the payments but remembered their ids
     * would refuse to record them again, and everything that calls this is a test or a demo about
     * to send the same fixture once more.
     */
    public void clear() {
        sentTransfers.clear();
        dispatchedIds.clear();
    }
}
