package cz.vsb.minibank.infrastructure.json.dto;


import java.util.ArrayList;
import java.util.List;


public class JsonAccount {
    public int id;
    public String iban;
    public double balance;
    public double dailyLimit;
    public List<Integer> transferIds = new ArrayList<>();
}