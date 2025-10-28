package cz.vsb.minibank.domain.exceptions;


public class InsufficientFundsException extends DomainException {
    public InsufficientFundsException(String msg) { super(msg); }
}