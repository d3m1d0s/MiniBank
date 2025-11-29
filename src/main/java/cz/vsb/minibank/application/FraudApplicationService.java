package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;

public class FraudApplicationService {
    private final TransferRepository transfers;
    private final FraudAlertRepository alerts;
    private final AccountRepository accounts;
    private final FeePolicy feePolicy;
    private final UnitOfWorkFactory uowFactory;

    public FraudApplicationService(TransferRepository transfers,
                                   FraudAlertRepository alerts,
                                   AccountRepository accounts,
                                   FeePolicy feePolicy,
                                   UnitOfWorkFactory uowFactory) {
        this.transfers = transfers; this.alerts = alerts; this.accounts = accounts; this.feePolicy = feePolicy;
        this.uowFactory = uowFactory;
    }

    /** UC 11 – Review Suspicious Transaction: APPROVE.
     *  Approves the fraud alert. If the transfer is still in CREATED state,
     *  the transfer is actually sent (fee computed by the injected FeePolicy).
     */
    public void approve(int transferId) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
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
            uow.commit();
            // If the status is WAITING_AUTH and an auth method is present, continue with UC 05 elsewhere.
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /** UC 11 – Review Suspicious Transaction: DECLINE.
     *  Marks the alert as suspicious with the provided reason and declines the transfer.
     */
    public void decline(int transferId, String reason) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            var alert = alerts.byTransferId(transferId).orElseThrow(() -> new RuntimeException("Alert not found"));
            alert.markSuspicious(reason);
            alerts.save(alert);

            var t = transfers.byId(transferId).orElseThrow(() -> new RuntimeException("Transfer not found"));
            t.decline(reason);
            transfers.save(t);
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /** UC 12 – Request Customer Confirmation (simplified).
     *  In a full implementation this would send a notification to the customer.
     *  Here we only update the reason to indicate we are waiting for confirmation.
     */
    public void requestCustomerConfirmation(int transferId) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            var alert = alerts.byTransferId(transferId).orElseThrow(() -> new RuntimeException("Alert not found"));
            // Notification would be sent here; we only update the reason for now.
            alert.markSuspicious("Waiting for customer confirmation");
            alerts.save(alert);
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }
}
