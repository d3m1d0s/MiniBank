package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when the current state of an object does not allow the requested operation.
 *
 * Ordering rule, and it is load-bearing: throw this only after the caller's right to SEE
 * the object has been established. A ConflictException reaching a caller who would have
 * received {@link NotFoundException} for the same id proves the id is real, which is
 * exactly the enumeration oracle NotFoundException exists to close. The same rule binds
 * {@link InvalidOtpException} and {@link InsufficientFundsException}, which are also
 * raised only after an object has been resolved.
 */
public class ConflictException extends DomainException {
    public ConflictException(String msg) { super(msg); }
}
