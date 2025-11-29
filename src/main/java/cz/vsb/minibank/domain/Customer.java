package cz.vsb.minibank.domain;


import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import cz.vsb.minibank.domain.lazy.LazyList;
import cz.vsb.minibank.domain.Account;



public class Customer {
    private int id;
    private String name;
    private String email;
    private Address address;
    private final List<Integer> accountIds = new ArrayList<>();
    private final List<Beneficiary> beneficiaries = new ArrayList<>();


    // lazy-loaded accounts based on accountIds
    private LazyList<Account> accountsLazy;


    public Customer(int id, String name, String email, Address address) {
        this.id = id; this.name = name; this.email = email; this.address = address;
    }


    public void addAccountId(int id) { accountIds.add(id); }
    public void addBeneficiary(Beneficiary b) { beneficiaries.add(b); }
    public void saveBeneficiary(Beneficiary b) {
        Objects.requireNonNull(b, "beneficiary");

        for (int i = 0; i < beneficiaries.size(); i++) {
            Beneficiary existing = beneficiaries.get(i);
            if (existing.id() == b.id()) {
                // replace existing entry (update)
                beneficiaries.set(i, b);
                return;
            }
        }

        // not found -> treat as new beneficiary
        beneficiaries.add(b);
    }
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


    public void attachAccounts(LazyList<Account> accounts) {
        this.accountsLazy = accounts;
    }

    /**
     * Lazily resolve all accounts for this customer.
     */
    public List<Account> accounts() {
        return (accountsLazy != null) ? accountsLazy.getAll() : List.of();
    }


}