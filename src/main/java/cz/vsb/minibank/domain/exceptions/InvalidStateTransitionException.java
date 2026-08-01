package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when a domain object is asked to perform an illegal state transition.
 *
 * Extends {@link ConflictException} so an aggregate's own guard and a service's
 * hand-rolled one answer with the same status and the same code.
 */
public class InvalidStateTransitionException extends ConflictException {
    public InvalidStateTransitionException(String msg) { super(msg); }
}
