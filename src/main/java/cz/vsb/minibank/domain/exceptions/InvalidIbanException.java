package cz.vsb.minibank.domain.exceptions;


public class InvalidIbanException extends DomainException {
    public InvalidIbanException(String msg) { super(msg); }
}