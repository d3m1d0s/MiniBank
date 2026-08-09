package cz.vsb.minibank.uow;

import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferObserver;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a domain event reaches its observers, and when it must not.
 *
 * This is the defect the aggregates stopped publishing for. Both repositories defer their writes
 * to commit, so an aggregate that notified from inside its own setter announced a change that had
 * not been written yet: a transaction that rolled back left audit lines for status transitions
 * that never reached the store, and the audit trail said things had happened that had not.
 *
 * Driven on the JSON backend so it needs no database and runs on a fresh clone. The unit of work
 * is what decides the timing and both implementations do it the same way, so the SQL side is the
 * same code path with a different persist.
 */
class DomainEventsAtCommitTest {

    /** Records what it was told about, so the test can assert on absence as well as presence. */
    private static final class RecordingObserver implements TransferObserver {
        final List<String> heard = new ArrayList<>();

        @Override
        public void onStatusChanged(Transfer transfer, TransferStatus oldStatus, TransferStatus newStatus) {
            heard.add(transfer.id() + ":" + oldStatus + "->" + newStatus);
        }
    }

    @TempDir
    Path tempDir;

    private Bootstrap infra;
    private RecordingObserver observer;

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());
        observer = new RecordingObserver();
        infra.events.register(observer);
    }

    @Test
    void aCommittedTransactionAnnouncesEveryTransitionItPersisted() {
        int id = inATransaction(t -> {
            t.requestAuthorization(null);
            t.decline("changed my mind");
        }, true);

        assertEquals(List.of(id + ":CREATED->WAITING_AUTH", id + ":WAITING_AUTH->DECLINED"),
                observer.heard,
                "both transitions, in the order they happened");
    }

    @Test
    void aRolledBackTransactionAnnouncesNothing() {
        inATransaction(t -> t.decline("never persisted"), false);

        assertTrue(observer.heard.isEmpty(),
                "a transition that was rolled back did not happen, and the audit trail must not"
                        + " say it did");
    }

    @Test
    void aTransactionThatThrowsBeforeCommitAnnouncesNothing() {
        UnitOfWork uow = infra.uowFactory.begin();
        assertThrows(IllegalStateException.class, () -> {
            try (UowScope __ = new UowScope(uow)) {
                Transfer t = newTransfer();
                t.decline("about to fail");
                infra.transfers.add(t);
                throw new IllegalStateException("something later in the use case failed");
            }
        });

        assertTrue(observer.heard.isEmpty(),
                "UowScope rolled this back on the way out, so nothing was persisted and nothing"
                        + " may be announced");
    }

    @Test
    void aSecondTransactionOnTheSameAggregateDoesNotRepeatTheFirstOnesEvents() {
        int id = inATransaction(t -> t.requestAuthorization(null), true);
        observer.heard.clear();

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow = scope.uow();
            Transfer t = infra.transfers.byId(id).orElseThrow();
            t.decline("second transaction");
            infra.transfers.save(t);
            uow.commit();
        }

        assertEquals(List.of(id + ":WAITING_AUTH->DECLINED"), observer.heard,
                "draining at commit is what keeps the first transaction's events out of the"
                        + " second one");
    }

    /**
     * Runs one transaction over a brand new transfer and either commits it or throws it away.
     *
     * @return the id of the transfer, which is real whether or not the transaction committed,
     *         because the sequence is drawn inside it
     */
    private int inATransaction(java.util.function.Consumer<Transfer> work, boolean commit) {
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            Transfer t = newTransfer();
            work.accept(t);
            infra.transfers.add(t);
            if (commit) {
                scope.uow().commit();
            } else {
                scope.uow().rollback();
            }
            return t.id();
        }
    }

    private Transfer newTransfer() {
        return new Transfer(infra.transfers.nextId(), 1, null,
                "CZ6508000000192000145399", Money.czk(100), "CZK");
    }
}
