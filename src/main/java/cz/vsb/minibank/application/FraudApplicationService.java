package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.NotFoundException;
import cz.vsb.minibank.domain.exceptions.ValidationException;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;

import java.time.Clock;
import java.time.Instant;

/**
 * Application service for fraud related use cases such as approving, declining and confirming suspicious transfers.
 *
 * Every way in here is role-gated, not owner-gated, and that is deliberate rather than the
 * oversight the ownership rule fixed next door: an analyst is supposed to reach every customer's alerts, so
 * there is no ownership rule to write.
 *
 * The gate is in the callers, not in this class, and that is worth stating plainly because it
 * is the kind of arrangement a reader assumes the other way round. There are three callers and
 * all three are accounted for:
 *
 * - Over HTTP, FraudController. Every endpoint there opens with requireRole, and decide reads
 *   the transfer id off the alert row rather than off the request.
 * - The console fraud menu, registered for FRAUD_ANALYST. ConsoleMenu checks that on the
 *   dispatch path and not only when drawing the menu, so the command cannot be typed past. In
 *   the legacy JSON mode effectiveRole answers CUSTOMER, so the menu is unreachable there
 *   rather than unguarded - the opposite of what this comment used to claim.
 * - DemoRunner, a script with no operator at all.
 *
 * What follows from that, and is the residual: a NEW caller gets no check. Moving the check in
 * here would close that, and was decided against. It would force an identity on the two callers
 * that have none - inventing an analyst for a mode with no users is worse than the gap - and
 * requireRole lives in the api package, so this class would either depend downward on it or
 * write the rule a second time.
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
     * Stamps fraud_alerts.resolved_at.
     *
     * Injected for the reason TransferApplicationService's is: an instant this project records
     * comes from a clock somebody can fix, not from Instant.now() buried in a service. It is
     * the same clock instance BootstrapServices hands the transfer service, so an alert's
     * resolved_at and its transfer's settled_at are readings of one clock.
     */
    private final Clock clock;

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
        this(transfers, alerts, uowFactory, Clock.system(TransferApplicationService.BANK_ZONE));
    }

    public FraudApplicationService(TransferRepository transfers,
                                   FraudAlertRepository alerts,
                                   UnitOfWorkFactory uowFactory,
                                   Clock clock) {
        this.transfers = transfers;
        this.alerts = alerts;
        this.uowFactory = uowFactory;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
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
     *
     * No analyst is recorded. This method has no HTTP route; its callers are the console fraud
     * menu, which in legacy JSON mode has no login, and the demo runner. Passing null rather
     * than a placeholder is the same decision the javadoc on this class already makes about the
     * missing role check: inventing an analyst for a mode with no users is worse than the gap.
     */
    public void approve(int transferId) {
        Instant decidedAt = clock.instant();

        try (UowScope scope = new UowScope(uowFactory.begin())) {
            var alert = alerts.byTransferId(transferId).orElseThrow(() -> new NotFoundException("Alert not found for transfer " + transferId));
            var t = transfers.byId(transferId).orElseThrow(() -> new NotFoundException("Transfer not found: " + transferId));

            alert.approve(null, decidedAt);
            alerts.save(alert);

            // Conditional because the customer may have cancelled the payment while it was
            // held. Clearing the alert is still the right record of the analyst's decision;
            // there is simply nothing left to release.
            if (t.status() == TransferStatus.HELD_FOR_REVIEW) {
                t.releaseForAuthorization();
                transfers.save(t);
            }
            scope.uow().commit();
        }
    }

    /**
     * UC 11 - Review Suspicious Transaction: DECLINE.
     * Marks the alert as suspicious with the provided reason and declines the transfer, unless
     * the money has already gone - in which case the verdict is recorded on the alert alone.
     *
     * No analyst is recorded, for the same reason {@link #approve(int)} records none.
     */
    public void decline(int transferId, String reason) {
        Instant decidedAt = clock.instant();

        try (UowScope scope = new UowScope(uowFactory.begin())) {
            var alert = alerts.byTransferId(transferId).orElseThrow(() -> new NotFoundException("Alert not found for transfer " + transferId));
            var t = transfers.byId(transferId).orElseThrow(() -> new NotFoundException("Transfer not found: " + transferId));

            alert.markSuspicious(reason, null, decidedAt);
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
            scope.uow().commit();
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

    /**
     * @param decidedBy the analyst's username, taken from the session by FraudController. The
     *        only route to this method is over HTTP behind requireRole(FRAUD_ANALYST), so it is
     *        always present here; the console's decisions come through approve/decline, which
     *        record no analyst because the legacy console has no login.
     */
    public void decideAndUpdateAlert(
            int alertId,
            String decisionRaw,
            String reason,
            String assignee,
            java.util.List<String> tags,
            String notes,
            String decidedBy
    ) {
        try (UowScope scope = new UowScope(uowFactory.begin())) {

            FraudAlert alert = alerts.byId(alertId)
                    .orElseThrow(() -> new NotFoundException("Fraud alert not found: " + alertId));

            int transferId = alert.transferId();

            String decision = java.util.Optional.ofNullable(decisionRaw)
                    .orElseThrow(() -> new ValidationException("Decision must be provided"))
                    .trim()
                    .toUpperCase(java.util.Locale.ROOT);

            // Validated before the switch, so a comma in a tag cannot cost the analyst their
            // verdict: raised inside the switch it would roll back an approve or decline that
            // had already been applied to the aggregate.
            java.util.List<String> cleanedTags = cleanTags(tags);
            Instant decidedAt = clock.instant();

            switch (decision) {
                case "APPROVE" -> {
                    // transferId came from the alert row, not from the request.
                    var t = transfers.byId(transferId).orElseThrow(() -> new DataIntegrityException(
                            "Fraud alert " + alertId + " points at missing transfer " + transferId));

                    alert.approve(decidedBy, decidedAt);
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

                    alert.markSuspicious(r, decidedBy, decidedAt);
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
            if (cleanedTags != null) {
                alert.replaceTags(cleanedTags);
            }
            if (notes != null) {
                alert.updateNotes(notes);
            }

            alerts.save(alert);

            scope.uow().commit();
        }
    }

    /**
     * The one place a tag is validated, and the only reason a tag can be refused.
     *
     * A comma is rejected because the two backends store tags differently and neither can
     * represent one: SqlFraudAlertRepository joins them with commas and splits on commas coming
     * back, so "high,risk" is written as one tag and read as two, while the JSON store keeps a
     * real list and reads back the one tag that was typed. The same alert would then answer the
     * same question differently depending on which store it came from. Refusing the character
     * is the smaller fix than escaping it or moving the column to a PostgreSQL array, and it
     * costs a tag vocabulary nothing - a comma inside a label is punctuation, not information.
     *
     * ValidationException maps to 400 VALIDATION_ERROR. The offending tag is in the exception
     * message, which reaches the log only; no handler echoes it.
     *
     * @return null when the caller sent no tags at all, which means "leave them alone" and is a
     *         different instruction from an empty list, which means "clear them"
     */
    private static java.util.List<String> cleanTags(java.util.List<String> tags) {
        if (tags == null) {
            return null;
        }
        java.util.List<String> cleaned = tags.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        for (String tag : cleaned) {
            if (tag.indexOf(',') >= 0) {
                throw new ValidationException("A fraud tag must not contain a comma: " + tag);
            }
        }
        return cleaned;
    }
}
