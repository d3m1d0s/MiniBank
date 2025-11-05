package cz.vsb.minibank.domain;


import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


public class Customer {
    private int id;
    private String name;
    private String email;
    private Address address;
    private final List<Integer> accountIds = new ArrayList<>();
    private final List<Beneficiary> beneficiaries = new ArrayList<>();


    public Customer(int id, String name, String email, Address address) {
        this.id = id; this.name = name; this.email = email; this.address = address;
    }


    public void addAccountId(int id) { accountIds.add(id); }
    public void addBeneficiary(Beneficiary b) { beneficiaries.add(b); }
    public void upsertBeneficiary(Beneficiary b) {
        Objects.requireNonNull(b, "beneficiary");
        for (int i = 0; i < this.beneficiaries.size(); i++) {
            if (this.beneficiaries.get(i).id() == b.id()) {
                this.beneficiaries.set(i, b);
                return;
            }
        }
        this.beneficiaries.add(b);
    }

    public int id() { return id; }
    public String name() { return name; }
    public String email() { return email; }
    public Address address() { return address; }
    public List<Integer> accountIds() { return accountIds; }
    public List<Beneficiary> beneficiaries() { return beneficiaries; }
}