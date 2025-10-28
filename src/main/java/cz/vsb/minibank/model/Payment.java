package cz.vsb.minibank.model;


public abstract class Payment {
    public String method; // e.g. CARD, OTP
    public double amount;
}