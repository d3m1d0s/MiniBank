package cz.vsb.minibank.domain.transfer;

/**
 * Observer for status changes of transfers.
 */
public interface TransferObserver {

    /**
     * Called whenever a transfer changes its status.
     *
     * @param transfer  the transfer whose status changed
     * @param oldStatus previous status (may be null for the first assignment)
     * @param newStatus new status
     */
    void onStatusChanged(Transfer transfer,
                         TransferStatus oldStatus,
                         TransferStatus newStatus);
}
