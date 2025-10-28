package cz.vsb.minibank.model;


public class Beneficiary {
    public int id;
    public String name;
    public String iban;
    public boolean trusted;


    public Beneficiary() {}
    public Beneficiary(int id, String name, String iban, boolean trusted) {
        this.id = id; this.name = name; this.iban = iban; this.trusted = trusted;
    }


    @Override public String toString() { return id + ": " + name + " [" + iban + "]" + (trusted?" (trusted)":""); }
}