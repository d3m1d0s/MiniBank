package cz.vsb.minibank.domain;


import cz.vsb.minibank.domain.value.Money;


public class CardPayment extends Payment {
    private String cardNumberMasked;


    public CardPayment() {}
    public CardPayment(Money amount, String maskedCard) {
        super("CARD", amount);
        this.cardNumberMasked = maskedCard;
    }


    public String cardNumberMasked() { return cardNumberMasked; }
}