package cz.vsb.minibank.domain.exceptions;


public class DailyLimitExceededException extends DomainException {
    public DailyLimitExceededException(String msg) { super(msg); }
}