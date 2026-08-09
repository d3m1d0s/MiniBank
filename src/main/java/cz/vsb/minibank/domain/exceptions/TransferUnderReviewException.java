package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when a transfer cannot be confirmed because a fraud alert on it is still open.
 *
 * A subtype of {@link ConflictException}, so Spring's ExceptionDepthComparator picks its own
 * handler over the generic one and the customer is told their payment is under review rather
 * than only that something changed - the same arrangement {@link SelfTransferNotAllowedException}
 * has over {@link ValidationException}. Subclassing also means it still answers 409 if that
 * handler were ever removed.
 *
 * Subject to the ordering rule on {@link ConflictException}: raised only after the caller's
 * right to see the transfer has been established.
 */
public class TransferUnderReviewException extends ConflictException {
    public TransferUnderReviewException(String msg) { super(msg); }
}
