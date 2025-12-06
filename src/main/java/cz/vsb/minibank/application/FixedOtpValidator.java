package cz.vsb.minibank.application;

/**
 * OtpValidator implementation that accepts a fixed set of demo codes.
 */
public class FixedOtpValidator implements OtpValidator {

    /**
     * Accepts the demo codes "0000" and "123456" and rejects all others.
     */
    @Override
    public boolean isValid(int transferId, String otp) {
        return otp != null && (otp.equals("0000") || otp.equals("123456"));
    }
}
