package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when a write is built on a read the store has since moved past.
 *
 * A6. The store refused an absolute balance computed from a figure that is no longer true,
 * rather than applying it on top of another transaction's committed change and destroying it.
 *
 * A subtype of {@link ConflictException} for two reasons. It really is a state conflict and
 * belongs on 409, and being a subtype lets RestExceptionHandler answer it with a message of its
 * own while the generic conflict handler stays where it is - the same arrangement
 * {@link TransferUnderReviewException} relies on. A plain RuntimeException here would have been
 * reported to the customer as INTERNAL_ERROR, which is false: nothing is broken and nothing was
 * charged.
 *
 * The message carries the account id and the version this transaction read. It reaches the
 * server log only; no handler echoes an exception message.
 *
 * {@link TransferChangedException} extends this for the same mechanism on the transfers row.
 * Anything thrown as this type rather than as that one is about an account, which is what lets
 * this class keep a message that promises nothing was charged.
 */
public class OptimisticLockException extends ConflictException {
    public OptimisticLockException(String msg) { super(msg); }
}
