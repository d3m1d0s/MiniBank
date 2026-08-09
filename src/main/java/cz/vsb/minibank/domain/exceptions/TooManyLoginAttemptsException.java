package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when a caller has spent its recent sign-in allowance and the next attempt is refused
 * without being checked.
 *
 * A rate condition rather than a banking rule, filed here for the same reason as
 * {@link TooManySessionsException}: {@code RestExceptionHandler} keeps one import root for
 * everything it maps. Deliberately not an authentication failure - the credential in the
 * refused request was never looked at, so answering AUTH_FAILED would tell a caller whose
 * password is right that it is wrong, and would send an honest one to reset a password that
 * works.
 */
public class TooManyLoginAttemptsException extends DomainException {
    public TooManyLoginAttemptsException(String msg) { super(msg); }
}
