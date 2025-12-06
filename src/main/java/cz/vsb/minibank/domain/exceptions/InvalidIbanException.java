package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when an IBAN value fails validation.
 */
public class InvalidIbanException extends DomainException {
    public InvalidIbanException(String msg) { super(msg); }
}
