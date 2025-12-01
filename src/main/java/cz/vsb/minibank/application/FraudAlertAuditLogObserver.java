package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertObserver;
import cz.vsb.minibank.domain.FraudAlertState;

import java.time.Instant;

/**
 * Observer that writes fraud alert state changes into the central audit log.
 */
public class FraudAlertAuditLogObserver implements FraudAlertObserver {

    @Override
    public void onStateChanged(FraudAlert alert,
                               FraudAlertState oldState,
                               FraudAlertState newState) {

        String msg = String.format(
                "%s FraudAlert %d for transfer %d: %s -> %s, reason=%s",
                Instant.now(),
                alert.id(),
                alert.transferId(),
                oldState,
                newState,
                alert.reason()
        );

        AppLogger.audit("audit.fraud", msg);
    }
}
