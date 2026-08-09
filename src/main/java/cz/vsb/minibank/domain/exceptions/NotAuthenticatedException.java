package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when an operation needs an authenticated user and there is none.
 */
public class NotAuthenticatedException extends DomainException {
    public NotAuthenticatedException(String msg) { super(msg); }
}
