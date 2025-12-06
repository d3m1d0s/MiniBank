package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Transfer;

/**
 * Gateway to the external payment network used to dispatch transfers.
 */
public interface PaymentNetworkGateway {

    /**
     * Sends the transfer to the external payment network.
     * Implementations should be idempotent for the same transfer ID where possible.
     */
    void send(Transfer transfer);
}
