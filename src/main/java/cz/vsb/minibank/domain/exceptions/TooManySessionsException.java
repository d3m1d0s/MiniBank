package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when the session store will not open another session.
 *
 * A capacity condition rather than a banking rule, filed here anyway so that
 * {@code RestExceptionHandler} keeps a single import root for everything it maps.
 * Deliberately not an authentication failure: the credentials were correct and the caller
 * has nothing to fix, so answering AUTH_FAILED would send them to change a password that
 * is not wrong.
 */
public class TooManySessionsException extends DomainException {
    public TooManySessionsException(String msg) { super(msg); }
}
