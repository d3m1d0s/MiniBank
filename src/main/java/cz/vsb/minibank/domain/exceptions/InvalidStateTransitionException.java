package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when a domain object is asked to perform an illegal state transition.
 */
public class InvalidStateTransitionException extends DomainException {
    public InvalidStateTransitionException(String msg) { super(msg); }
}
