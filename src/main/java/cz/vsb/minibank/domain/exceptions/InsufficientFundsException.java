package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when an account does not have enough funds to complete an operation.
 */
public class InsufficientFundsException extends DomainException {
    public InsufficientFundsException(String msg) { super(msg); }
}
