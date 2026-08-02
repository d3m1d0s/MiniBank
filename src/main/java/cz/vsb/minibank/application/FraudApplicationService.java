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
 *
 * The state guards below - FraudAlert's, and the transfer's - are checks against a snapshot,
 * not locks. SqlUnitOfWorkFactory.begin only clears auto-commit; there is no isolation level
 * set anywhere and no SELECT ... FOR UPDATE in this project, so two analysts committing at the
 * same instant can still interleave, exactly as a customer authorizing and an analyst deciding
 * can. What the guards do buy is that a stale decision is refused rather than silently applied
 * on any interleaving the two units of work actually observe. Row locking is its own item.
 */
public class FraudApplicationService {
    private final TransferRepository transfers;
    private final FraudAlertRepository alerts;
    private final UnitOfWorkFactory uowFactory;

    /**
     * No AccountRepository, no FeePolicy, and no gateway. This service decides alerts; it does
     * not move money, and the only method that ever did - the one that settled a transfer an
     * analyst had approved - is gone with the branch that called it. That is what makes the
     * absent payment gateway moot rather than merely unfixed: a class that never settles has
     * nothing to dispatch.
     */
    public FraudApplicationService(TransferRepository transfers,
                                   FraudAlertRepository alerts,
                                   UnitOfWorkFactory uowFactory) {
        this.transfers = transfers;
        this.alerts = alerts;
        this.uowFactory = uowFactory;
    }

    /**
     * UC 11 - Review Suspicious Transaction: APPROVE.
     *
     * Clears the alert and releases the transfer for the customer's own confirmation step. It
     * does not send the money: the customer still has to authorize it, and that path re-checks
     * the balance and the day's ceiling.
     *
     * The branch this replaces settled a transfer that was still CREATED, which no alerted
     * transfer ever was - RuleBasedRiskService made createAlert strictly imply requireAuth - so
     * "approve the suspicious transaction" was a use case with no reachable body. Alerted
     * transfers are now HELD_FOR_REVIEW by construction and this branch runs on all of them.
     */
    public void approve(int transferId) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            var alert = alerts.byTransferId(transferId).orElseThrow(() -> new NotFoundException("Alert not found for transfer " + transferId));
            var t = transfers.byId(transferId).orElseThrow(() -> new NotFoundException("Transfer not found: " + transferId));

            alert.approve();
            alerts.save(alert);

            // Conditional because the customer may have cancelled the payment while it was
            // held. Clearing the alert is still the right record of the analyst's decision;
            // there is simply nothing left to release.
            if (t.status() == TransferStatus.HELD_FOR_REVIEW) {
                t.releaseForAuthorization();
                transfers.save(t);
            }
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /**
     * UC 11 - Review Suspicious Transaction: DECLINE.
     * Marks the alert as suspicious with the provided reason and declines the transfer, unless
     * the money has already gone - in which case the verdict is recorded on the alert alone.
     */
    public void decline(int transferId, String reason) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            var alert = alerts.byTransferId(transferId).orElseThrow(() -> new NotFoundException("Alert not found for transfer " + transferId));
            var t = transfers.byId(transferId).orElseThrow(() -> new NotFoundException("Transfer not found: " + transferId));

            alert.markSuspicious(reason);
            alerts.save(alert);

            // A verdict on money that has already left is a record, not a reversal. Calling
            // decline() there raised a 409 that rolled the verdict back with it, so APPROVE was
            // the only decision a sent transfer would accept and confirmed fraud was filed as
            // OK. Nothing here reverses a settlement; there is no path that could.
            //
            // DECLINED is excluded too: the transfer is already stopped, and declining it again
            // would overwrite the customer's own "Canceled by customer" with the analyst's
            // wording, rewriting the record of why their payment stopped.
            if (t.status() != TransferStatus.SENT && t.status() != TransferStatus.DECLINED) {
                t.decline(reason);
                transfers.save(t);
            }
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /**
     * UC 12 - Request Customer Confirmation.
     *
     * Leaves the alert open and the transfer held. It is not a verdict: the customer's
     * confirmation step is exactly what APPROVE unlocks, so resolving the alert here would
     * leave the transfer held with nothing able to release it - approve() refuses a resolved
     * alert - and the customer's only way out would be to cancel. Marking it suspicious also
     * overwrote the risk reason the rules produced, which was the only record of why the alert
     * existed.
     *
     * Over HTTP this is now the metadata-only action: the switch branch changes no state and
     * the assignee/tags/notes block after it runs and commits. That makes it the one route by
     * which an analyst can annotate an already-decided alert, since approve() and
     * markSuspicious() both refuse a second verdict and take the metadata down with them.
     *
     * The console carries no metadata, so this method has nothing left to do but prove the
     * alert exists. No unit of work: both backends serve a read with no ambient one.
     */
    public void requestCustomerConfirmation(int transferId) {
        alerts.byTransferId(transferId).orElseThrow(
                () -> new NotFoundException("Alert not found for transfer " + transferId));
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
                    // transferId came from the alert row, not from the request.
                    var t = transfers.byId(transferId).orElseThrow(() -> new DataIntegrityException(
                            "Fraud alert " + alertId + " points at missing transfer " + transferId));

                    alert.approve();
                    alerts.save(alert);

                    // Clears the transfer for the customer's confirmation step; it does not
                    // send the money. Conditional because the customer may have cancelled it
                    // while it was held. A second analyst on a stale queue never reaches here:
                    // alert.approve() refuses an already-decided alert first.
                    if (t.status() == TransferStatus.HELD_FOR_REVIEW) {
                        t.releaseForAuthorization();
                        transfers.save(t);
                    }
                }
                case "DECLINE" -> {
                    String r = (reason != null && !reason.isBlank()) ? reason : "Declined by fraud analyst";
                    var t = transfers.byId(transferId).orElseThrow(() -> new DataIntegrityException(
                            "Fraud alert " + alertId + " points at missing transfer " + transferId));

                    alert.markSuspicious(r);
                    alerts.save(alert);

                    // Recorded, not reversed, once the money has left. Refusing it here used to
                    // roll the assignee, tags and notes below back with the verdict, so a
                    // settled transfer accepted APPROVE and nothing else and confirmed fraud
                    // was filed as OK. DECLINED is excluded so an analyst's wording does not
                    // overwrite the customer's own cancellation reason.
                    if (t.status() != TransferStatus.SENT && t.status() != TransferStatus.DECLINED) {
                        t.decline(r);
                        transfers.save(t);
                    }
                }
                case "REQUEST_CONFIRMATION" -> {
                    // Not a verdict, and deliberately a no-op on both aggregates. The
                    // confirmation step this asks for is the one APPROVE unlocks, so resolving
                    // the alert here would strand the transfer held with nothing able to
                    // release it, and it destroyed the risk reason that says why it was raised.
                    //
                    // What it does do is fall through to the metadata block below, which is why
                    // it survives: it is the only route that can attach an assignee, tags or
                    // notes to an alert that has already been decided.
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
