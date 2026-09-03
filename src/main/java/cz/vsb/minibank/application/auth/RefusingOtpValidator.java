package cz.vsb.minibank.application.auth;

/**
 * The validator a run without the demo profile gets: no one time password provider is configured,
 * so no code is accepted.
 * <p>
 * It exists because the alternative is worse than a refusal. {@link FixedOtpValidator} accepts two
 * compile-time constants, and left in place outside the demo it would be a published password on
 * every payment this bank authorizes. Refusing everything stops the authorization step instead,
 * which is what a bank with no provider wired up actually has, and it says so at startup rather
 * than at the moment a customer is trying to confirm a payment.
 * <p>
 * {@link OtpValidator} is the seam where a real provider goes, and replacing this is the whole of
 * that work.
 */
public class RefusingOtpValidator implements OtpValidator {

    @Override
    public boolean isValid(int transferId, String otp) {
        return false;
    }
}
