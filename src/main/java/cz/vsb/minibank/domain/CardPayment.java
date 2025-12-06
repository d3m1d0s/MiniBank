package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.value.Money;

/**
 * Payment authorization based on card details.
 */
public class CardPayment extends Payment {

    /**
     * Masked card number used only for display and audit purposes.
     */
    private String cardNumberMasked;

    public CardPayment() {}

    public CardPayment(Money amount, String maskedCard) {
        super("CARD", amount);
        this.cardNumberMasked = maskedCard;
    }

    public String cardNumberMasked() { return cardNumberMasked; }
}
