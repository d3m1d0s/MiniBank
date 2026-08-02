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
 *
 * Every method here is role-gated, not owner-gated, and that is deliberate rather than the
 * oversight A3 fixed next door: an analyst is supposed to reach every customer's alerts, so
 * there is no ownership rule to write. Over HTTP the only route in is FraudController.decide,
 * which calls requireRole and reads the transfer id off the alert row rather than off the
 * request. The three single-argument methods below have no HTTP route at all; their only
 * caller is the console fraud menu, which in the legacy JSON mode has no login to check a
 * role against. That is a separate backlog item and it wants the legacy console to gain a
 * login first - inventing an analyst for a mode with no users would be worse than the gap.
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
            // Unreachable today, and only because RuleBasedRiskService makes createAlert
            // (untrusted, over 10 000) strictly imply requireAuth (untrusted, over 5 000), so
            // no alerted transfer is ever CREATED. Raising AUTH_THRESHOLD_FOR_UNTRUSTED above
            // ALERT_THRESHOLD_FOR_UNTRUSTED would silently give the analyst an unguarded debit.
            // A9's day-total term is OR-ed into requireAuth and can only widen it, so the
            // implication still holds. The other half of the invariant is that no stored row
            // arrives here in CREATED with an alert attached: since A9 this is the one debit
            // site with no daily-limit check, because this service has neither a RiskService
            // nor a Clock. Attaching an alert to a CREATED transfer means giving it both.
            if (t.status() == TransferStatus.CREATED) {
                sendApproved(t);
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
                        sendApproved(t);
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

    /**
     * Sends a transfer an analyst approved while it was still CREATED.
     *
     * Both approve branches held the same six lines. Folding them into one method means a
     * threshold change re-opens one code path rather than two that can disagree about who
     * receives the money.
     *
     * No gateway call, unlike the customer paths: this service has never had a gateway, so an
     * approved payment to an IBAN outside this bank still reaches nobody. That gap is older
     * than the credit leg and is left alone here; what changes is that an approved payment to
     * an account of this bank now arrives.
     */
    private void sendApproved(Transfer t) {
        var acc = accounts.byId(t.sourceAccountId()).orElseThrow(() -> new DataIntegrityException(
                "Transfer " + t.id() + " points at missing account " + t.sourceAccountId()));
        Account destination = accounts.inBankByIban(t.targetIbanSnapshot()).orElse(null);
        t.send(acc, destination, feePolicy);
        transfers.save(t);
        // Ascending id order, the same rule the customer paths follow, so a settlement started
        // by an analyst cannot deadlock against one started by a customer.
        accounts.saveBothInIdOrder(acc, destination);
    }

}
