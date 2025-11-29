package cz.vsb.minibank.infrastructure.json.dto;


public class JsonFraudAlert {
    public int id;
    public int transferId;
    public String state;
    public String reason;
    public String createdAt;
}