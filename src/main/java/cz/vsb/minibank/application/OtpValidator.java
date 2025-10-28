package cz.vsb.minibank.application;


public interface OtpValidator {
    boolean isValid(int transferId, String otp);
}