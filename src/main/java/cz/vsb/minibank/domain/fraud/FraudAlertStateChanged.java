package cz.vsb.minibank.domain.fraud;
import cz.vsb.minibank.domain.event.DomainEvent;
import cz.vsb.minibank.domain.transfer.TransferStatusChanged;

/**
 * A fraud alert moved from one state to another.
 *
 * Carries the aggregate for the same reason {@link TransferStatusChanged} does, and with the
 * same consequence.
 */
public record FraudAlertStateChanged(FraudAlert alert,
                                     FraudAlertState oldState,
                                     FraudAlertState newState) implements DomainEvent {
}
