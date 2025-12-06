package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simple in memory stub of PaymentNetworkGateway used for development and tests.
 */
public final class FakePaymentNetworkGateway implements PaymentNetworkGateway {

    private final List<Transfer> sentTransfers = new ArrayList<>();

    @Override
    public void send(Transfer transfer) {
        sentTransfers.add(transfer);
    }

    /**
     * Returns an unmodifiable view of the sent transfers for tests or demos.
     */
    public List<Transfer> sentTransfers() {
        return Collections.unmodifiableList(sentTransfers);
    }

    /**
     * Clears the in memory history of sent transfers.
     */
    public void clear() {
        sentTransfers.clear();
    }
}
