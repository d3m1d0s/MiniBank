package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when a transfer exceeds the daily limit of an account.
 */
public class DailyLimitExceededException extends DomainException {
    public DailyLimitExceededException(String msg) { super(msg); }
}
