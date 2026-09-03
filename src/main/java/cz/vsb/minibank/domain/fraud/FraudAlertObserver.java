package cz.vsb.minibank.domain.fraud;

/**
 * Observer for state changes of fraud alerts.
 */
public interface FraudAlertObserver {

    /**
     * Called whenever a fraud alert changes its state.
     *
     * @param alert    fraud alert whose state changed
     * @param oldState previous state (may be null for the initial state)
     * @param newState new state
     */
    void onStateChanged(FraudAlert alert,
                        FraudAlertState oldState,
                        FraudAlertState newState);
}
