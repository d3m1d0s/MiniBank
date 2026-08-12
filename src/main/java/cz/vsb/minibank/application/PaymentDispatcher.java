package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.DispatchState;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferObserver;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Hands a settled payment to the payment network, after the transaction that settled it has
 * committed.
 *
 * This is the half of the phantom dispatch that could not be fixed by removing a line.
 * {@code TransferApplicationService.settle} used to call the gateway from inside the open unit of
 * work. Both backends defer every row write to commit, so a commit that then failed - the account
 * version guard, the transfers version guard, a driver failure, a JSON save that could not be
 * published - rolled the debit and the SENT status back while the payment had already been handed
 * to the network. The customer was answered 409, told nothing had been charged and invited to send
 * the payment again, which dispatched the same money a second time. {@link Transfer#send} now
 * records the obligation as a state on the transfers row, written in the same unit of work as the
 * debit, and this class discharges it once that unit of work has committed.
 *
 * <h2>It must open its own unit of work, and this is the rule that is easy to get wrong</h2>
 *
 * Both units of work publish after they have completed, not before: {@code SqlUnitOfWork.commit}
 * runs its mutations and {@code connection.commit()} inside a try whose {@code finally} calls
 * {@code cleanup()}, and only the statement after that try - outside it, outside the finally -
 * calls {@code events.publishAll}. By the time an observer is entered, cleanup has already set
 * {@code completed}, cleared the identity map and closed the JDBC connection.
 * {@code JsonUnitOfWork.commit} has the same shape around {@code finish()}, which releases the
 * store lock.
 *
 * {@link cz.vsb.minibank.infrastructure.uow.UowContext} still holds that finished unit of work,
 * because the application service publishes from inside its own {@code UowScope}. So a repository
 * call made straight from an observer runs against a transaction that is over: the identity map
 * probe every repository opens with refuses it outright, and a call that got past the probe would
 * put a statement on a closed connection. Either way the customer's committed payment is reported
 * as a 500. Every method below therefore opens a {@link UowScope} of its own first, which binds a
 * fresh unit of work and restores the finished one on the way out.
 *
 * <h2>Send, then mark, at least once</h2>
 *
 * The gateway is called first and the row is marked afterwards, so a crash between the two leaves
 * the row PENDING and the next sweep offers the payment again. That is safe because
 * {@link PaymentNetworkGateway} asks its implementations to be idempotent on the transfer id.
 * The reverse order would be at-most-once, which on this side of the ledger means a payment the
 * bank has debited and nobody will ever send.
 *
 * The mark goes through the version-guarded transfer upsert, so it can be refused with
 * {@link cz.vsb.minibank.domain.exceptions.TransferChangedException} when something else wrote the
 * row first. That is caught and left as it is, deliberately: the money has already gone to the
 * network, the row is still PENDING, and PENDING is exactly the state that gets it offered again.
 * A second offer is absorbed by the gateway's idempotency, whereas letting the refusal out would
 * turn a successful dispatch into a failure report about work that is done.
 *
 * <h2>It never throws at its caller</h2>
 *
 * An observer that throws would report a failure for a payment that has committed and moved money.
 * {@code DomainEventBus} contains that already, but it contains it by printing to stderr, and this
 * one has to reach the audit log, so the containment is here where the sink is. A dispatch that
 * failed is logged and the row is left owing, which is the whole point of storing the obligation.
 *
 * <h2>No scheduler</h2>
 *
 * {@link #sweepPending()} is called once by each composition root, at startup, after the demo data
 * is in place. There is no background thread anywhere in this project and this does not add the
 * first one: a payment that fails to dispatch waits for the next start, and every path that could
 * produce one is already a path where the process is in trouble.
 */
public final class PaymentDispatcher implements TransferObserver {

    private final PaymentNetworkGateway gateway;
    private final TransferRepository transfers;
    private final UnitOfWorkFactory uowFactory;

    public PaymentDispatcher(PaymentNetworkGateway gateway,
                             TransferRepository transfers,
                             UnitOfWorkFactory uowFactory) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.uowFactory = Objects.requireNonNull(uowFactory, "uowFactory");
    }

    /**
     * Takes the id of a payment that has just settled and nothing else off the event.
     *
     * The aggregate it arrives with belongs to the unit of work that has just finished, so the id
     * is the only part of it this class is willing to believe. Everything the decision needs is
     * read again, inside a transaction of this class's own, from the row that transaction actually
     * committed.
     *
     * The filter is on the status alone. An intra-bank payment reaches SENT as well and costs one
     * read that answers nothing, and that is the price of not deciding anything from an aggregate
     * whose transaction is over.
     */
    @Override
    public void onStatusChanged(Transfer transfer,
                                TransferStatus oldStatus,
                                TransferStatus newStatus) {
        if (newStatus != TransferStatus.SENT) {
            return;
        }
        dispatch(transfer.id());
    }

    /**
     * Offers the network every payment that has left this bank and that no gateway has been handed.
     *
     * What a crash leaves behind. A process that dies between the commit and the dispatch, or
     * between the dispatch and the mark, leaves a row saying the money is gone and nobody has been
     * told; this is what tells them. Called once at each composition root, and the ordering
     * requirement is that it runs after whatever seeds data, because a seeded settled payment out
     * of this bank is one of these rows.
     *
     * The ids are collected first and each payment is then put through the same path a freshly
     * committed one takes, rather than dispatched from the aggregates the query returned. Two
     * reasons: those aggregates belong to the reading transaction, which is over by then, and one
     * path with one set of guards cannot come to disagree with itself.
     *
     * A failure to dispatch one payment is contained, so the rest of the sweep still runs. A
     * failure to read the store at all is not: at that point nothing else about this process works
     * either, and a startup that cannot see its own data should say so rather than come up quietly
     * owing an unknown number of payments.
     */
    public void sweepPending() {
        List<Integer> owed = pendingIds();
        if (owed.isEmpty()) {
            return;
        }

        AppLogger.info("payment.dispatch", "Offering the payment network " + owed.size()
                + " settled payments it has not been handed yet: " + owed);
        for (int transferId : owed) {
            dispatch(transferId);
        }
    }

    /**
     * One payment, from the row rather than from anybody's aggregate.
     *
     * Three steps in three separate calls, and the middle one is outside any unit of work on
     * purpose. On the JSON backend a unit of work holds the store lock for its whole life, so a
     * gateway called from inside one blocks every other thread that touches the store for as long
     * as the network takes to answer - which is what {@code JsonDataStore} warns must not happen
     * between begin and commit. The gateway takes the aggregate as a payload and writes nothing,
     * so handing it one whose transaction has ended costs nothing.
     *
     * The row is read twice, once to decide and once to mark. The second read is what makes the
     * version guard on the mark a guard against the row as it stands now rather than as it stood
     * before a network call of unknown duration.
     */
    private void dispatch(int transferId) {
        try {
            Transfer payment = pendingPayment(transferId);
            if (payment == null) {
                return;
            }

            gateway.send(payment);
            recordDispatch(transferId);
        } catch (RuntimeException failure) {
            // Deliberately the last word on this payment. Either it never reached the network or
            // it reached it and the mark did not stick; the row says PENDING in both cases and the
            // next sweep offers it again, which is why neither case may be raised at a caller that
            // has already been told its payment went through.
            AppLogger.error("payment.dispatch", "Transfer " + transferId
                    + " could not be dispatched, or was dispatched and not recorded as such."
                    + " It stays pending and the next sweep offers it again", failure);
        }
    }

    /**
     * The payment as the store holds it, or null when it owes the network nothing any more.
     *
     * Null covers three answers that need no distinction here: the row is gone, it never owed a
     * dispatch, or something else has already made it. Read-only, so there is nothing to commit
     * and {@link UowScope#close()} ends the transaction on the way out.
     */
    private Transfer pendingPayment(int transferId) {
        try (UowScope scope = new UowScope(uowFactory.begin())) {
            Transfer t = transfers.byId(transferId).orElse(null);
            return (t != null && t.dispatchState() == DispatchState.PENDING) ? t : null;
        }
    }

    /**
     * Records that the network has this payment.
     *
     * The state is checked again on the freshly read row and not assumed from the read that
     * decided to send: two dispatches of one payment can be in flight at once - a sweep and an
     * observer, on a row a previous run left pending - and the second one arriving here must be a
     * no-op rather than a second write of a row it has nothing to change on.
     */
    private void recordDispatch(int transferId) {
        try (UowScope scope = new UowScope(uowFactory.begin())) {
            Transfer t = transfers.byId(transferId).orElse(null);
            if (t == null || t.dispatchState() != DispatchState.PENDING) {
                return;
            }

            t.markDispatched();
            transfers.save(t);
            scope.uow().commit();
        }
    }

    /**
     * The ids of everything still owed, taken in one read and carried out of it as numbers.
     */
    private List<Integer> pendingIds() {
        try (UowScope scope = new UowScope(uowFactory.begin())) {
            List<Integer> ids = new ArrayList<>();
            for (Transfer t : transfers.awaitingDispatch()) {
                ids.add(t.id());
            }
            return ids;
        }
    }
}
