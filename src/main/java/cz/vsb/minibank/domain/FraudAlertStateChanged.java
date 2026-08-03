package cz.vsb.minibank.domain;

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
