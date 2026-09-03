package cz.vsb.minibank.application.payment;

import cz.vsb.minibank.domain.transfer.Transfer;

import java.net.URI;
import java.net.http.HttpClient;

/**
 * Placeholder for an HTTP based implementation of PaymentNetworkGateway.
 * The current version only documents where the real integration would live.
 */
public final class HttpPaymentNetworkGateway implements PaymentNetworkGateway {

    private final HttpClient client = HttpClient.newHttpClient();
    private final URI endpoint;

    public HttpPaymentNetworkGateway(URI endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public void send(Transfer transfer) {
        throw new UnsupportedOperationException("HTTP payment gateway not implemented yet");
    }
}
