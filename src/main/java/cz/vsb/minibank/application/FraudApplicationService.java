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

    /** UC 11 – Review Suspicious Transaction: APPROVE */
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
            // pokud je WAITING_AUTH a má authMethod, pokračuje UC 05
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /** UC 11 – Review Suspicious Transaction: DECLINE */
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

    /** UC 12 – Request Customer Confirmation (zjednodušeně) */
    public void requestCustomerConfirmation(int transferId) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            var alert = alerts.byTransferId(transferId).orElseThrow(() -> new RuntimeException("Alert not found"));
            // zde by bylo odeslání notifikace; pouze aktualizujeme důvod
            alert.markSuspicious("Waiting for customer confirmation");
            alerts.save(alert);
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }
}