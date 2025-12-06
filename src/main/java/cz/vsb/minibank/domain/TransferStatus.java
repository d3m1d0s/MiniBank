package cz.vsb.minibank.domain;

/**
 * Lifecycle states of an outgoing transfer.
 */
public enum TransferStatus {
    CREATED,
    WAITING_AUTH,
    SENT,
    DECLINED
}
