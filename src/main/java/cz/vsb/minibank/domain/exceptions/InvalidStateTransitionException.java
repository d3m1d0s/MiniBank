package cz.vsb.minibank.domain.exceptions;


public class InvalidStateTransitionException extends DomainException {
    public InvalidStateTransitionException(String msg) { super(msg); }
}