package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.value.Money;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The aggregates record what happens to them, and the recording is what a transition produces.
 *
 * Replaces the test that asserted a transition fired an observer. It cannot any more: a transition
 * now tells nobody, which is the change. What it must still do is leave a record, and this is the
 * half of the pattern that lives in the domain.
 */
class AggregateDomainEventTest {

    @Test
    void requestingAuthorizationRecordsTheTransitionAndTellsNobody() {
        Transfer t = aTransfer();

        t.requestAuthorization(null);

        List<DomainEvent> recorded = t.drainDomainEvents();
        assertEquals(1, recorded.size());
        TransferStatusChanged event = assertInstanceOf(TransferStatusChanged.class, recorded.get(0));
        assertSame(t, event.transfer());
        assertEquals(TransferStatus.CREATED, event.oldStatus());
        assertEquals(TransferStatus.WAITING_AUTH, event.newStatus());
    }

    @Test
    void decliningRecordsTheTransition() {
        Transfer t = aTransfer();

        t.decline("test decline");

        List<DomainEvent> recorded = t.drainDomainEvents();
        assertEquals(1, recorded.size());
        TransferStatusChanged event = assertInstanceOf(TransferStatusChanged.class, recorded.get(0));
        assertEquals(TransferStatus.CREATED, event.oldStatus());
        assertEquals(TransferStatus.DECLINED, event.newStatus());
    }

    @Test
    void severalTransitionsAreRecordedInTheOrderTheyHappened() {
        Transfer t = aTransfer();

        t.requestAuthorization(null);
        t.decline("changed my mind");

        List<DomainEvent> recorded = t.drainDomainEvents();
        assertEquals(2, recorded.size());
        assertEquals(TransferStatus.WAITING_AUTH,
                ((TransferStatusChanged) recorded.get(0)).newStatus());
        assertEquals(TransferStatus.DECLINED,
                ((TransferStatusChanged) recorded.get(1)).newStatus());
    }

    @Test
    void drainingEmptiesTheAggregateSoNothingIsPublishedTwice() {
        Transfer t = aTransfer();
        t.decline("once");

        assertEquals(1, t.drainDomainEvents().size());
        assertTrue(t.drainDomainEvents().isEmpty(),
                "a second drain must find nothing, or a transfer carried into another"
                        + " transaction would announce this one's change as that one's");
    }

    @Test
    void aRefusedTransitionRecordsNothing() {
        Transfer t = aTransfer();
        t.decline("already declined");
        t.drainDomainEvents();

        try {
            t.requestAuthorization(null);
        } catch (RuntimeException expected) {
            // DECLINED is terminal; the point is what the aggregate did not record.
        }

        assertTrue(t.drainDomainEvents().isEmpty(),
                "a transition the aggregate refused did not happen and must leave no event");
    }

    @Test
    void anAlertRecordsItsOwnVerdict() {
        FraudAlert alert = new FraudAlert(1, 2, "New beneficiary + high amount");

        alert.markSuspicious("looks wrong", "anna.analyst", java.time.Instant.now());

        List<DomainEvent> recorded = alert.drainDomainEvents();
        assertEquals(1, recorded.size());
        FraudAlertStateChanged event = assertInstanceOf(FraudAlertStateChanged.class, recorded.get(0));
        assertSame(alert, event.alert());
        assertEquals(FraudAlertState.NEW, event.oldState());
        assertEquals(FraudAlertState.SUSPICIOUS, event.newState());
    }

    private static Transfer aTransfer() {
        return new Transfer(1, 1, null, "CZ6508000000192000145399", Money.czk(100));
    }
}
