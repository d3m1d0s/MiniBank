package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertObserver;
import cz.vsb.minibank.domain.FraudAlertState;

import java.time.Instant;

/**
 * Simple observer that logs fraud alert state changes.
 */
public class FraudAlertAuditLogObserver implements FraudAlertObserver {

    @Override
    public void onStateChanged(FraudAlert alert,
                               FraudAlertState oldState,
                               FraudAlertState newState) {

        System.out.printf(
                "[FRAUD-AUDIT] %s FraudAlert %d for transfer %d: %s -> %s, reason: %s%n",
                Instant.now(),
                alert.id(),
                alert.transferId(),
                oldState,
                newState,
                alert.reason()
        );
    }
}
