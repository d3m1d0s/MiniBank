package cz.vsb.minibank.application.auth;

/**
 * Validates one time passwords used to authorize transfers.
 */
public interface OtpValidator {

    /**
     * Returns true when the provided OTP is valid for the given transfer.
     *
     * @param transferId identifier of the transfer being authorized
     * @param otp one time password provided by the user
     * @return true if the OTP is accepted
     */
    boolean isValid(int transferId, String otp);
}
