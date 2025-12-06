package cz.vsb.minibank.infrastructure.json.dto;


import java.util.ArrayList;
import java.util.List;

public class JsonFraudAlert {
    public int id;
    public int transferId;
    public String state;
    public String reason;
    public String createdAt;

    public Integer riskScore;
    public String assignee;
    public List<String> tags = new ArrayList<>();
    public String notes;
}