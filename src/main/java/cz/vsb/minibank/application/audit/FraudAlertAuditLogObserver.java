package cz.vsb.minibank.application.audit;

import cz.vsb.minibank.domain.fraud.FraudAlert;
import cz.vsb.minibank.domain.fraud.FraudAlertObserver;
import cz.vsb.minibank.domain.fraud.FraudAlertState;

/**
 * Observer that writes fraud alert state changes into the central audit log.
 *
 * The message carries no timestamp of its own. {@link AppLogger} stamps every line it writes,
 * so one here produced two per line, and the inner one came from {@code Instant.now()} rather
 * than from the clock the rest of the application is given.
 */
public class FraudAlertAuditLogObserver implements FraudAlertObserver {

    @Override
    public void onStateChanged(FraudAlert alert,
                               FraudAlertState oldState,
                               FraudAlertState newState) {

        String msg = String.format(
                "FraudAlert %d for transfer %d: %s -> %s, reason=%s",
                alert.id(),
                alert.transferId(),
                oldState,
                newState,
                alert.reason()
        );

        AppLogger.audit("audit.fraud", msg);
    }
}
