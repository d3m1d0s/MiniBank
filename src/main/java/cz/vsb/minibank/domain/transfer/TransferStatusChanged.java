package cz.vsb.minibank.domain.transfer;
import cz.vsb.minibank.domain.event.DomainEvent;

/**
 * A transfer moved from one status to another.
 *
 * Carries the aggregate itself rather than a copy of its fields, because
 * {@link TransferObserver} has always been handed the transfer and the audit line reads its
 * amount and its source account. The consequence is worth knowing: an observer sees the transfer
 * as it is when the event is published, not as it was when the event was recorded. Within one
 * transaction those differ only if the same transfer changed twice, in which case both events
 * describe the same final object with different status pairs.
 */
public record TransferStatusChanged(Transfer transfer,
                                    TransferStatus oldStatus,
                                    TransferStatus newStatus) implements DomainEvent {
}
