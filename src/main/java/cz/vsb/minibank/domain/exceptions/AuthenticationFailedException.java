package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when the credentials presented at sign-in do not identify a user.
 * An unknown username and a wrong password are the same failure here on purpose.
 */
public class AuthenticationFailedException extends DomainException {
    public AuthenticationFailedException(String msg) { super(msg); }
}
