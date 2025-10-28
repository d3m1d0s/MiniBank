package cz.vsb.minibank.domain;


import cz.vsb.minibank.domain.value.IBAN;


public class Beneficiary {
    private int id;
    private String name;
    private IBAN iban;
    private boolean trusted;


    public Beneficiary(int id, String name, IBAN iban, boolean trusted) {
        this.id = id; this.name = name; this.iban = iban; this.trusted = trusted;
    }


    public int id() { return id; }
    public String name() { return name; }
    public IBAN iban() { return iban; }
    public boolean trusted() { return trusted; }
}