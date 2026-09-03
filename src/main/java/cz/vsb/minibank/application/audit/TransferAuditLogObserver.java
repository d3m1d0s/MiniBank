package cz.vsb.minibank.application.audit;

import cz.vsb.minibank.domain.transfer.Transfer;
import cz.vsb.minibank.domain.transfer.TransferObserver;
import cz.vsb.minibank.domain.transfer.TransferStatus;

/**
 * Observer that writes transfer status changes into the central audit log.
 */
public class TransferAuditLogObserver implements TransferObserver {

    @Override
    public void onStatusChanged(Transfer transfer,
                                TransferStatus oldStatus,
                                TransferStatus newStatus) {

        String msg = String.format(
                "Transfer %d: %s -> %s, amount=%s, sourceAccountId=%d",
                transfer.id(),
                oldStatus,
                newStatus,
                transfer.amount(),
                transfer.sourceAccountId()
        );

        AppLogger.audit("audit.transfer", msg);
    }
}
