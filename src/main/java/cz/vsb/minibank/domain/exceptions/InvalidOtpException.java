package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when a one-time password does not match the pending authorization.
 *
 * The failed attempt is spent and committed before this is raised, so the three-attempt
 * limit survives the refusal. See TransferApplicationService.authorizePayment.
 */
public class InvalidOtpException extends DomainException {
    public InvalidOtpException(String msg) { super(msg); }
}
