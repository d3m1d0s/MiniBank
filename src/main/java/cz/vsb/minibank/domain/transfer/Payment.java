package cz.vsb.minibank.domain.transfer;

import cz.vsb.minibank.domain.value.Money;

/**
 * Base class for payment authorization methods such as cards or OTP.
 */
public abstract class Payment {

    /**
     * Identifier of the payment method, for example "CARD" or "OTP".
     */
    protected String method;

    /**
     * Amount associated with the payment authorization.
     */
    protected Money amount;

    protected Payment() {}

    protected Payment(String method, Money amount) {
        this.method = method;
        this.amount = amount;
    }

    public String method() { return method; }
    public Money amount() { return amount; }
}
