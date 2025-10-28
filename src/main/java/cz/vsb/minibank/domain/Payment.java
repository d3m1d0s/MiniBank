package cz.vsb.minibank.domain;


import cz.vsb.minibank.domain.value.Money;


public abstract class Payment {
    protected String method; // e.g. "CARD", "OTP"
    protected Money amount;


    protected Payment() {}
    protected Payment(String method, Money amount) {
        this.method = method; this.amount = amount;
    }


    public String method() { return method; }
    public Money amount() { return amount; }
}