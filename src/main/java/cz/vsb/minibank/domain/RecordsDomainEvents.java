package cz.vsb.minibank.domain;

import java.util.List;

/**
 * An aggregate that records what happened to it so the unit of work can publish it at commit.
 *
 * Implemented by {@link Transfer} and {@link FraudAlert}, and read only by the two unit of work
 * implementations, which walk their identity map for aggregates that carry this.
 */
public interface RecordsDomainEvents {

    /**
     * Removes and returns everything recorded since the last drain, oldest first.
     *
     * Draining rather than reading, so an aggregate that survives into a second transaction -
     * the identity map is per transaction, but nothing stops a caller holding a reference -
     * cannot publish the same event twice.
     */
    List<DomainEvent> drainDomainEvents();
}
