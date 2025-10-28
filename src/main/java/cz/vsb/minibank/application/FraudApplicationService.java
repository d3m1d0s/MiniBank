package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;

public class FraudApplicationService {
    private final TransferRepository transfers;
    private final FraudAlertRepository alerts;
    private final AccountRepository accounts;
    private final FeePolicy feePolicy;

    public FraudApplicationService(TransferRepository transfers,
                                   FraudAlertRepository alerts,
                                   AccountRepository accounts,
                                   FeePolicy feePolicy) {
        this.transfers = transfers; this.alerts = alerts; this.accounts = accounts; this.feePolicy = feePolicy;
    }

    /** UC 11 – Review Suspicious Transaction: APPROVE */
    public void approve(int transferId) {
        var alert = alerts.byTransferId(transferId).orElseThrow(() -> new RuntimeException("Alert not found"));
        alert.approve();
        alerts.save(alert);

        var t = transfers.byId(transferId).orElseThrow(() -> new RuntimeException("Transfer not found"));
        if (t.status() == TransferStatus.CREATED) {
            var acc = accounts.byId(t.sourceAccountId()).orElseThrow(() -> new RuntimeException("Account not found"));
            t.send(acc, feePolicy);
            transfers.save(t);
            accounts.save(acc);
        }
        // pokud je WAITING_AUTH a má authMethod, pokračuje UC 05
    }

    /** UC 11 – Review Suspicious Transaction: DECLINE */
    public void decline(int transferId, String reason) {
        var alert = alerts.byTransferId(transferId).orElseThrow(() -> new RuntimeException("Alert not found"));
        alert.markSuspicious(reason);
        alerts.save(alert);

        var t = transfers.byId(transferId).orElseThrow(() -> new RuntimeException("Transfer not found"));
        t.decline(reason);
        transfers.save(t);
    }

    /** UC 12 – Request Customer Confirmation (zjednodušeně) */
    public void requestCustomerConfirmation(int transferId) {
        var alert = alerts.byTransferId(transferId).orElseThrow(() -> new RuntimeException("Alert not found"));
        // zde by bylo odeslání notifikace; pouze aktualizujeme důvod
        alert.markSuspicious("Waiting for customer confirmation");
        alerts.save(alert);
    }
}