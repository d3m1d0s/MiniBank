package cz.vsb.minibank.model;


import java.util.ArrayList;
import java.util.List;


public class Account {
    public int id;
    public String iban;
    public double balance;
    public double dailyLimit;
    public int ownerId; // связь на Customer


    public List<Integer> transferIds = new ArrayList<>();


    public Account() {}
    public Account(int id, String iban, double balance, double dailyLimit, int ownerId) {
        this.id = id; this.iban = iban; this.balance = balance; this.dailyLimit = dailyLimit; this.ownerId = ownerId;
    }


    @Override public String toString() {
        return id + " [" + iban + "] bal=" + balance + ", limit=" + dailyLimit + ", owner=" + ownerId;
    }
}