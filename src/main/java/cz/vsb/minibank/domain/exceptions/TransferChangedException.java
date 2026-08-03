package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when a write to a transfer is built on a status the store has since moved past.
 *
 * The same mechanism as {@link OptimisticLockException} on a different row, and a subtype of it
 * because that is exactly what it is. It answers separately because the two cannot share a
 * message. The account one says outright that nothing was charged and invites the customer to
 * send the payment again, which is true when a balance write loses a race. On a transfer it can
 * be false in the way that matters most: a cancel that loses to an authorization is refused
 * while the other tab has already charged the customer, so "nothing was charged" is wrong and
 * "send it again" is an invitation to pay twice.
 *
 * What this one can promise instead is narrower and always true: this request changed nothing,
 * and the payment is not in the state the caller was looking at. The remedy is to look again,
 * not to retry.
 *
 * The message carries the transfer id and the version this transaction read. It reaches the
 * server log only; no handler echoes an exception message.
 */
public class TransferChangedException extends OptimisticLockException {
    public TransferChangedException(String msg) { super(msg); }
}
