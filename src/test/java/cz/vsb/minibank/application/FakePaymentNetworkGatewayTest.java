package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.value.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FakePaymentNetworkGatewayTest {

    @Test
    void sendStoresTransferInMemory() {
        FakePaymentNetworkGateway gateway = new FakePaymentNetworkGateway();
        Transfer t = new Transfer(
                1,
                10,
                null,
                "CZ0401000000000000000000",
                Money.czk(1_000),
                "CZK"
        );

        gateway.send(t);

        assertEquals(1, gateway.sentTransfers().size());
        assertSame(t, gateway.sentTransfers().get(0));
    }

    @Test
    void clearRemovesAllStoredTransfers() {
        FakePaymentNetworkGateway gateway = new FakePaymentNetworkGateway();
        Transfer t = new Transfer(
                1,
                10,
                null,
                "CZ0401000000000000000000",
                Money.czk(500),
                "CZK"
        );
        gateway.send(t);
        assertFalse(gateway.sentTransfers().isEmpty());

        gateway.clear();

        assertTrue(gateway.sentTransfers().isEmpty());
    }
}
