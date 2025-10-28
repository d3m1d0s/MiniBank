package cz.vsb.minibank.model;


import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;


public class Transfer {
    public int id;
    public int sourceAccountId; // Account (source)
    public String targetIban; // cílový IBAN (может быть из Beneficiary)
    public double amount;
    public String currency = "CZK";
    public String status = "CREATED"; // CREATED / WAITING_AUTH / SENT / DECLINED
    public String createdAt; // ISO


    public Payment authMethod; // závislost


    public Transfer() {}


    public Transfer(int id, int sourceAccountId, String targetIban, double amount) {
        this.id = id; this.sourceAccountId = sourceAccountId; this.targetIban = targetIban; this.amount = amount;
        this.createdAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }


    public double calculateFee() {
// Примерная формула комиссии для cv2
        return amount > 5000 ? Math.round(amount * 0.005 * 100.0) / 100.0 : 0.0;
    }


    @Override public String toString() {
        return "#" + id + " " + sourceAccountId + " -> " + targetIban +
                ", amt=" + amount + " " + currency + ", status=" + status;
    }
}