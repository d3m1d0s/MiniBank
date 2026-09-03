package cz.vsb.minibank.application.fraud;

import cz.vsb.minibank.domain.customer.*;
import cz.vsb.minibank.domain.fee.*;
import cz.vsb.minibank.domain.fraud.*;
import cz.vsb.minibank.domain.transfer.*;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.NotFoundException;
import cz.vsb.minibank.domain.exceptions.ValidationException;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;

import java.time.Clock;
import java.time.Instant;
import cz.vsb.minibank.application.payment.TransferApplicationService;

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
     * Marks the alert as suspicious and declines the transfer, unless the money has already gone -
     * in which case the verdict is recorded on the alert alone.
     *
     * No analyst is recorded, for the same reason {@link #approve(int)} records none.
     *
     * @param comment what the operator gave as the ground for the refusal. It is recorded on the
     *        alert as the analyst's comment, beside rather than inside the sentence the rules
     *        wrote, and it is what the payer is told about their stopped payment. Required: a
     *        refusal that stops a payment for good says why, and no caller of this method gets a
     *        sentence written for it
     */
    public void decline(int transferId, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new ValidationException(
                    "A declined alert needs a reason: say why this payment is refused");
        }

        Instant decidedAt = clock.instant();

        try (UowScope scope = new UowScope(uowFactory.begin())) {
            var alert = alerts.byTransferId(transferId).orElseThrow(() -> new NotFoundException("Alert not found for transfer " + transferId));
            var t = transfers.byId(transferId).orElseThrow(() -> new NotFoundException("Transfer not found: " + transferId));

            alert.markSuspicious(comment, null, decidedAt);
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
                // The instant the alert was marked with, not a second reading: the verdict and the
                // refusal it caused are one event and a case file should not show them apart.
                t.decline(comment, decidedAt);
                transfers.save(t);
            }
            scope.uow().commit();
        }
    }

    /**
     * ANNOTATE, the third decision, taken from the console.
     *
     * Leaves the alert open and the transfer held. It is not a verdict: the customer's
     * confirmation step is exactly what APPROVE unlocks, so resolving the alert here would
     * leave the transfer held with nothing able to release it - approve() refuses a resolved
     * alert - and the customer's only way out would be to cancel. Marking it suspicious also
     * overwrote the risk reason the rules produced, which was the only record of why the alert
     * existed.
     *
     * ONE NAME, everywhere. The use case this began as was called Request Customer Confirmation
     * and this method carried that name until now, while the wire and both desks called the same
     * action ANNOTATE. Two names for one decision is how the console and the desks came to
     * describe different things to their operators: the console offered "request", which promises
     * a message to the customer that nothing anywhere sends. Over HTTP this is the ANNOTATE branch
     * of {@link #decideAndUpdateAlert}, which records the analyst's comment, appends the note
     * they wrote and changes
     * no state, and that is the one route to an already-decided alert, since approve() and
     * markSuspicious() both refuse a second verdict and take the writing down with them.
     *
     * The console carries neither a comment nor a note, so this method has nothing left to do but
     * prove the alert exists. No unit of work: both backends serve a read with no ambient one.
     */
    public void annotate(int transferId) {
        alerts.byTransferId(transferId).orElseThrow(
                () -> new NotFoundException("Alert not found for transfer " + transferId));
    }

    /**
     * A decision, the comment the analyst gave for it, and optionally one note for the case file.
     *
     * TWO DIFFERENT THINGS, and keeping them apart is what this signature is for. The comment
     * belongs to the decision: it is replaced when a later decision replaces the verdict, exactly
     * as decided_by and resolved_at are. The note belongs to the case: it is appended, it names
     * its author and its moment, and nothing ever removes it. They used to be one field and one
     * column between them - the comment appended into the risk reason, the note overwriting
     * whatever a colleague had written - which is how one line came to say two things and the
     * other lost everything but the last press.
     *
     * @param comment what the analyst wrote with this decision, or null or blank for none, which
     *        is the common case on an APPROVE. DECLINE is the exception and refuses a blank one:
     *        a refusal that stops a payment for good says why, and nothing here writes a sentence
     *        on the analyst's behalf
     * @param note one entry for the journal, or null or blank for none. Written under decidedBy
     *        and stamped from the same clock reading as the verdict, so a decision and the note
     *        taken with it agree about when they happened
     * @param decidedBy the analyst's username, taken from the session by FraudController. The
     *        only route to this method is over HTTP behind requireRole(FRAUD_ANALYST), so it is
     *        always present here; the console's decisions come through approve/decline, which
     *        record no analyst because the legacy console has no login.
     */
    public void decideAndUpdateAlert(
            int alertId,
            String decisionRaw,
            String comment,
            String note,
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

            Instant decidedAt = clock.instant();

            switch (decision) {
                case "APPROVE" -> {
                    // transferId came from the alert row, not from the request.
                    var t = transfers.byId(transferId).orElseThrow(() -> new DataIntegrityException(
                            "Fraud alert " + alertId + " points at missing transfer " + transferId));

                    // The comment travels into the verdict and is written inside it, below its
                    // guard, so a verdict that was refused leaves no trace of the refused caller's
                    // wording on somebody else's alert.
                    alert.approve(comment, decidedBy, decidedAt);

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
                    // A refusal says why, or it is not taken. This stands before anything is
                    // written, so a decision refused here leaves the alert and the transfer as
                    // they were.
                    //
                    // What it replaces was a substitution: a blank box became "Declined by fraud
                    // analyst" on the payer's copy. That is the bank answering a question the
                    // analyst was asked and did not answer, on the one decision here that stops
                    // somebody's money and cannot be taken back, and it read on the customer's
                    // screen exactly as a reason somebody had given. The desk now keeps its
                    // Decline control dead until the comment box has something in it; this is the
                    // same rule where the desk cannot be trusted to hold it, on a direct call.
                    if (comment == null || comment.isBlank()) {
                        throw new ValidationException(
                                "A declined alert needs a reason: say why this payment is refused");
                    }
                    String reason = comment.trim();

                    var t = transfers.byId(transferId).orElseThrow(() -> new DataIntegrityException(
                            "Fraud alert " + alertId + " points at missing transfer " + transferId));

                    alert.markSuspicious(reason, decidedBy, decidedAt);

                    // Recorded, not reversed, once the money has left. Refusing it here used to
                    // roll the writing below back with the verdict, so a settled transfer accepted
                    // APPROVE and nothing else and confirmed fraud was filed as OK. DECLINED is
                    // excluded so an analyst's wording does not overwrite the customer's own
                    // cancellation reason.
                    //
                    // The payer and the alert are told the same thing now, which is the analyst's
                    // own sentence and nothing the bank wrote for them.
                    if (t.status() != TransferStatus.SENT && t.status() != TransferStatus.DECLINED) {
                        // Stamped with the instant the alert above was, for the reason the desk's
                        // other decline path is: one decision, one moment on both records.
                        t.decline(reason, decidedAt);
                        transfers.save(t);
                    }
                }
                case "ANNOTATE", "REQUEST_CONFIRMATION" -> {
                    // Not a verdict, and deliberately no state change on either aggregate.
                    // Resolving the alert here would strand the transfer held with nothing able to
                    // release it - only APPROVE unlocks the customer's confirmation step - and
                    // marking it suspicious destroyed the risk reason that says why it was raised.
                    //
                    // It writes exactly what an analyst typed and nothing else: the comment here,
                    // and the note through the append below. That is why it survives at all - it
                    // is the only route that reaches an alert somebody has already decided, since
                    // approve and markSuspicious both refuse a second verdict and would take the
                    // writing down with them. ANNOTATE is that and nothing else, which is why
                    // REQUEST_CONFIRMATION lost the name: it asked for a confirmation nobody was
                    // ever sent. The old spelling stays accepted so the rename can reach the two
                    // desks in either order.
                    alert.recordDecisionComment(comment);
                }
                default -> throw new ValidationException("Unsupported decision: " + decisionRaw);
            }

            // One save for the whole decision. It used to be two - the verdict, then a second
            // pass that wrote the notes blob over whatever a colleague had left - and the second
            // is gone with the blob: the journal below is an insert of its own and touches no
            // column on this row.
            alerts.save(alert);

            // The note, if the analyst wrote one. Appended, never replacing anything, under their
            // own name and at the same instant the verdict carries. Blank is the same as absent:
            // the desks send an empty box on most decisions, and an entry that says nothing is not
            // a fact about the case.
            if (note != null && !note.isBlank()) {
                alerts.appendNote(
                        new FraudAlertNote(alertId, decidedBy, decidedAt, note.trim()));
            }

            scope.uow().commit();
        }
    }

    /**
     * Puts an alert in an analyst's name, or takes it out of everybody's.
     *
     * A method of its own rather than a parameter on the decision above, because assignment is
     * not a verdict and the two have different lifetimes: an analyst takes an alert when they
     * start looking at it and decides it when they have finished, and an alert that has already
     * been decided can still be handed to somebody to follow up. Folded into the decision it was
     * also unable to express the half that matters most to a queue, which is giving an alert
     * back: a blank assignee there meant "leave it alone", so nothing could ever clear one.
     *
     * The caller decides who, and the only caller over HTTP passes the signed-in analyst, never
     * a name off the request. That is what makes this route safe to leave open to every analyst:
     * taking an alert and releasing it are the two things a queue needs, and neither of them can
     * put a colleague's name on your work. An alert already held by somebody else is taken
     * rather than refused, deliberately: an analyst who has gone home must not be able to hold a
     * queue hostage.
     *
     * The write is guarded like every other alert write, so an assignment built on a stale read
     * is refused with the 409 that sends the analyst back to the alert rather than silently
     * winning over a colleague's.
     *
     * @param assignee the analyst's username; null or blank releases the alert, which is the one
     *                 instruction the decision route could not carry
     */
    public void assign(int alertId, String assignee) {
        String holder = (assignee == null || assignee.isBlank()) ? null : assignee.trim();

        try (UowScope scope = new UowScope(uowFactory.begin())) {
            FraudAlert alert = alerts.byId(alertId)
                    .orElseThrow(() -> new NotFoundException("Fraud alert not found: " + alertId));

            alert.assignTo(holder);
            alerts.save(alert);

            scope.uow().commit();
        }
    }
}
