package cz.vsb.minibank.domain.transfer;

/**
 * What a transfer owes the external payment network.
 *
 * Null is the third value and the common one: this payment owes the network nothing. Every
 * transfer that has not settled carries it, and so does every intra-bank one, which credits its
 * destination account in the same transaction as the debit and reaches no gateway at all. Absent
 * rather than a NONE constant, so that a row written before this state existed reads as what it
 * truly is - a payment with nothing outstanding - instead of needing a backfill that would have to
 * decide something about it. db/migrate/transfer-dispatch-state.sql says what deciding it wrongly
 * would cost.
 *
 * Two live values, because a dispatch that is owed and a dispatch that has happened are the only
 * two facts a retry has to tell apart. There is deliberately no FAILED: a dispatch that does not
 * go through stays {@link #PENDING} and is offered again, and nothing anywhere in this project
 * compensates or reverses a payment that has already left.
 */
public enum DispatchState {

    /**
     * This payment has settled, it leaves the bank, and no gateway has been handed it yet.
     *
     * Written by {@link Transfer#send} at the instant the money moves, so it is registered in the
     * same unit of work as the debit: a commit that fails takes the intent with it, and there is
     * no state in which the bank believes it owes a dispatch for money it never moved.
     */
    PENDING,

    /**
     * A gateway has been handed this payment.
     *
     * Written after the dispatch returns rather than before it, which is what makes the pair
     * at-least-once: a crash between the two leaves PENDING, and the payment is offered to the
     * network a second time. That is safe only because {@link
     * cz.vsb.minibank.application.payment.PaymentNetworkGateway} asks its implementations to be idempotent
     * on the transfer id, which is exactly the guarantee the fake one keeps.
     */
    DISPATCHED
}
