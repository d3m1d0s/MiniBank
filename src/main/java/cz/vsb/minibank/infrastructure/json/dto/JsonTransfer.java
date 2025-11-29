package cz.vsb.minibank.infrastructure.json.dto;


public class JsonTransfer {
    public int id;
    public int sourceAccountId;
    public Integer beneficiaryId;
    public String targetIbanSnapshot;
    public double amount;
    public String currency;
    public String status;
    public String createdAt;
    public String authMethod;
    public String cardNumberMasked;
    public String declineReason;
}