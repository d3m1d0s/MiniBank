package cz.vsb.minibank.domain.event;

/**
 * Something that happened to an aggregate, recorded on it and published after the transaction
 * that caused it has committed.
 *
 * An event is not a notification. An aggregate records one the moment the change happens and
 * knows nothing about who will hear it or when; {@link DomainEventBus} decides that, and the unit
 * of work decides when. That indirection is the whole point: both repositories defer their writes
 * to commit, so an aggregate that published as it mutated announced changes that had not been
 * persisted and, on a rolled-back transaction, never would be.
 */
public interface DomainEvent {
}
