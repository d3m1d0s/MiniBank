package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when a payment would take a day's outflow past the source account's daily ceiling.
 *
 * A ValidationException for the same reason {@link SelfTransferNotAllowedException} is one: it
 * already answers 400 without a handler of its own, and the dedicated type exists so the
 * response can name the rule that stopped the payment instead of only saying that something
 * was invalid. The version A11 deleted extended DomainException, which would fall through to
 * handleRuntime and answer 500 today.
 */
public class DailyLimitExceededException extends ValidationException {
    public DailyLimitExceededException(String msg) { super(msg); }
}
