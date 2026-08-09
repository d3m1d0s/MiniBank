package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when a monetary amount is not a valid value for the requested operation.
 */
public class InvalidAmountException extends DomainException {
    public InvalidAmountException(String msg) { super(msg); }
}
