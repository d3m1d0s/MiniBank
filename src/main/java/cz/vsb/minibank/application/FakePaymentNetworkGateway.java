package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simple in-memory stub of PaymentNetworkGateway used for development and tests.
 */
public final class FakePaymentNetworkGateway implements PaymentNetworkGateway {

    private final List<Transfer> sentTransfers = new ArrayList<>();

    @Override
    public void send(Transfer transfer) {
        sentTransfers.add(transfer);
    }

    /** Exposes the history of sent transfers for tests / demo. */
    public List<Transfer> sentTransfers() {
        return Collections.unmodifiableList(sentTransfers);
    }

    /** Clears the in-memory history. */
    public void clear() {
        sentTransfers.clear();
    }
}
