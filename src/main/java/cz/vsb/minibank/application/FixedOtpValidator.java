package cz.vsb.minibank.application;


public class FixedOtpValidator implements OtpValidator {
    @Override public boolean isValid(int transferId, String otp) {
        return otp != null && (otp.equals("0000") || otp.equals("123456"));
    }
}