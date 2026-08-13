package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when a write to a fraud alert is built on a verdict the store has since moved past.
 *
 * The same mechanism as {@link OptimisticLockException} on a third row, and a subtype of it
 * because that is exactly what it is. It answers separately for the reason
 * {@link TransferChangedException} does: the three cannot share a message. The account one tells
 * a customer that nothing was charged and invites them to send the payment again. The transfer
 * one tells a customer to look at their payment. This one addresses an analyst, and the write
 * that beat theirs may have been the opposite decision on the same alert, so the only safe thing
 * it can say is that this request applied nothing and the alert must be read again before it is
 * decided again.
 *
 * The message carries the alert id and the version this transaction read. It reaches the server
 * log only; no handler echoes an exception message.
 */
public class FraudAlertChangedException extends OptimisticLockException {
    public FraudAlertChangedException(String msg) { super(msg); }
}
