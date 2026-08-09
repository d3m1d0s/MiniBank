package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when a payment names the source account's own IBAN as its destination.
 *
 * It is a ValidationException so that it already answers 400 without a handler of its own;
 * the dedicated type exists so the response can say what is actually wrong instead of the
 * catalogue's generic wording. Until there was a credit leg, such a transfer debited the
 * source and credited nobody, so the money was destroyed rather than moved.
 */
public class SelfTransferNotAllowedException extends ValidationException {
    public SelfTransferNotAllowedException(String msg) { super(msg); }
}
