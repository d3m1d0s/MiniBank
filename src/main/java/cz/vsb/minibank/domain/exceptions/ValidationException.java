package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when caller-supplied input is missing, malformed or out of range.
 */
public class ValidationException extends DomainException {
    public ValidationException(String msg) { super(msg); }
}
