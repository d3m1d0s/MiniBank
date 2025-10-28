package cz.vsb.minibank.model;


import java.util.ArrayList;
import java.util.List;


public class Customer {
    public int id;
    public String name;
    public String email;
    public Address address; // kompozice


    // jednoduchá asociace přes id-ссылки на счета
    public List<Integer> accountIds = new ArrayList<>();


    // agregace — сохранённые получатели
    public List<Beneficiary> beneficiaries = new ArrayList<>();


    public Customer() {}
    public Customer(int id, String name, String email, Address address) {
        this.id = id; this.name = name; this.email = email; this.address = address;
    }


    @Override public String toString() {
        return id + ": " + name + " <" + email + ">, " + address;
    }
}