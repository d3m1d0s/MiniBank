package cz.vsb.minibank.domain;

/**
 * Lifecycle states of an outgoing transfer.
 */
public enum TransferStatus {
    CREATED,

    /**
     * An alert is open on this transfer. The customer cannot confirm it, however valid their
     * code, until an analyst decides. No authorization window is running: the five minutes are
     * the customer's time to type a code, and they must not run out while the alert waits in a
     * queue nobody is watching.
     *
     * Placed between CREATED and WAITING_AUTH because that is the order a held transfer
     * travels. Nothing reads ordinal() or values(), so the position is free. Fifteen
     * characters, so transfers.status VARCHAR(32) needs no schema change.
     */
    HELD_FOR_REVIEW,

    WAITING_AUTH,
    SENT,
    DECLINED
}
