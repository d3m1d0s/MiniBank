package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.NotFoundException;
import cz.vsb.minibank.domain.exceptions.ValidationException;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;

/**
 * Application service for fraud related use cases such as approving, declining and confirming suspicious transfers.
 */
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

    /**
     * UC 11 - Review Suspicious Transaction: APPROVE.
     * Approves the fraud alert and, if the transfer is still in CREATED state, sends the transfer with the configured fee policy.
     */
    public void approve(int transferId) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            var alert = alerts.byTransferId(transferId).orElseThrow(() -> new NotFoundException("Alert not found for transfer " + transferId));
            alert.approve();
            alerts.save(alert);

            var t = transfers.byId(transferId).orElseThrow(() -> new NotFoundException("Transfer not found: " + transferId));
            if (t.status() == TransferStatus.CREATED) {
                var acc = accounts.byId(t.sourceAccountId()).orElseThrow(() -> new DataIntegrityException(
                        "Transfer " + transferId + " points at missing account " + t.sourceAccountId()));
                t.send(acc, feePolicy);
                transfers.save(t);
                accounts.save(acc);
            }
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /**
     * UC 11 - Review Suspicious Transaction: DECLINE.
     * Marks the alert as suspicious with the provided reason and declines the transfer.
     */
    public void decline(int transferId, String reason) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            var alert = alerts.byTransferId(transferId).orElseThrow(() -> new NotFoundException("Alert not found for transfer " + transferId));
            alert.markSuspicious(reason);
            alerts.save(alert);

            var t = transfers.byId(transferId).orElseThrow(() -> new NotFoundException("Transfer not found: " + transferId));
            t.decline(reason);
            transfers.save(t);
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /**
     * UC 12 - Request Customer Confirmation (simplified).
     * Updates the alert reason to indicate the system is waiting for customer confirmation.
     */
    public void requestCustomerConfirmation(int transferId) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            var alert = alerts.byTransferId(transferId).orElseThrow(() -> new NotFoundException("Alert not found for transfer " + transferId));
            alert.markSuspicious("Waiting for customer confirmation");
            alerts.save(alert);
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    public void decideAndUpdateAlert(
            int alertId,
            String decisionRaw,
            String reason,
            String assignee,
            java.util.List<String> tags,
            String notes
    ) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {

            FraudAlert alert = alerts.byId(alertId)
                    .orElseThrow(() -> new NotFoundException("Fraud alert not found: " + alertId));

            int transferId = alert.transferId();

            String decision = java.util.Optional.ofNullable(decisionRaw)
                    .orElseThrow(() -> new ValidationException("Decision must be provided"))
                    .trim()
                    .toUpperCase(java.util.Locale.ROOT);

            switch (decision) {
                case "APPROVE" -> {
                    alert.approve();
                    alerts.save(alert);

                    // transferId came from the alert row, not from the request.
                    var t = transfers.byId(transferId).orElseThrow(() -> new DataIntegrityException(
                            "Fraud alert " + alertId + " points at missing transfer " + transferId));
                    if (t.status() == TransferStatus.CREATED) {
                        var acc = accounts.byId(t.sourceAccountId()).orElseThrow(() -> new DataIntegrityException(
                                "Transfer " + transferId + " points at missing account " + t.sourceAccountId()));
                        t.send(acc, feePolicy);
                        transfers.save(t);
                        accounts.save(acc);
                    }
                }
                case "DECLINE" -> {
                    String r = (reason != null && !reason.isBlank()) ? reason : "Declined by fraud analyst";
                    alert.markSuspicious(r);
                    alerts.save(alert);

                    var t = transfers.byId(transferId).orElseThrow(() -> new DataIntegrityException(
                            "Fraud alert " + alertId + " points at missing transfer " + transferId));
                    t.decline(r);
                    transfers.save(t);
                }
                case "REQUEST_CONFIRMATION" -> {
                    alert.markSuspicious("Waiting for customer confirmation");
                    alerts.save(alert);
                }
                default -> throw new ValidationException("Unsupported decision: " + decisionRaw);
            }

            // Metadata update (still inside same UoW)
            if (assignee != null && !assignee.isBlank()) {
                alert.assignTo(assignee.trim());
            }
            if (tags != null) {
                java.util.List<String> cleaned = tags.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                alert.replaceTags(cleaned);
            }
            if (notes != null) {
                alert.updateNotes(notes);
            }

            alerts.save(alert);

            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

}
