package cz.vsb.minibank.domain.exceptions;

/**
 * Base runtime exception type for domain layer errors.
 */
public class DomainException extends RuntimeException {
    public DomainException(String message) { super(message); }
}
