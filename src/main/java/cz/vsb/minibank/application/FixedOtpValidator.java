package cz.vsb.minibank.application;

/**
 * Demo implementation of {@link OtpValidator} that accepts two compile-time
 * constants so the authorization flow can be exercised without an SMS or TOTP
 * provider. It performs no verification of any kind and must never be used
 * outside a local demonstration; {@link OtpValidator} is the seam where a real
 * implementation would go.
 */
public class FixedOtpValidator implements OtpValidator {

    /** The codes this demo validator accepts. Not secret and not generated. */
    public static final String DEMO_OTP = "0000";
    public static final String DEMO_OTP_ALTERNATE = "123456";

    @Override
    public boolean isValid(int transferId, String otp) {
        return DEMO_OTP.equals(otp) || DEMO_OTP_ALTERNATE.equals(otp);
    }
}
