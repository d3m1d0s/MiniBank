package cz.vsb.minibank.model;


public class CardPayment extends Payment {
    public String cardNumber; // хранить маскированным в демо


    public CardPayment() {}
    public CardPayment(double amount, String maskedCard) {
        this.method = "CARD"; this.amount = amount; this.cardNumber = maskedCard;
    }
}