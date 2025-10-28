package cz.vsb.minibank.domain.exceptions;


public class AuthorizationFailedException extends DomainException {
    public AuthorizationFailedException(String msg) { super(msg); }
}