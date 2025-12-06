package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when authentication or authorization of a user fails.
 */
public class AuthorizationFailedException extends DomainException {
    public AuthorizationFailedException(String msg) { super(msg); }
}
