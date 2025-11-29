package cz.vsb.minibank.infrastructure.json.dto;


import java.util.ArrayList;
import java.util.List;


public class JsonCustomer {
    public int id;
    public String name;
    public String email;
    public JsonAddress address;
    public List<Integer> accountIds = new ArrayList<>();
    public List<JsonBeneficiary> beneficiaries = new ArrayList<>();
}