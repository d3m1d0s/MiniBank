package cz.vsb.minibank.domain.repository;

import cz.vsb.minibank.domain.FraudAlert;

import java.util.List;
import java.util.Optional;

/**
 * Repository abstraction for fraud alerts.
 */
public interface FraudAlertRepository {

    /**
     * Returns the next technical identifier for a new fraud alert.
     */
    int nextId();

    /**
     * Adds a new fraud alert.
     */
    void add(FraudAlert a);

    /**
     * Updates an existing fraud alert.
     */
    void save(FraudAlert a);

    /**
     * Finds a fraud alert by identifier.
     */
    Optional<FraudAlert> byId(int id);

    /**
     * Finds a fraud alert by associated transfer identifier.
     */
    Optional<FraudAlert> byTransferId(int transferId);

    /**
     * Returns all fraud alerts.
     */
    List<FraudAlert> all();
}
