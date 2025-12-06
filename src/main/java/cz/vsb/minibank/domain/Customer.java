package cz.vsb.minibank.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import cz.vsb.minibank.domain.lazy.LazyList;
import cz.vsb.minibank.domain.Account;

/**
 * Customer aggregate with accounts and beneficiaries.
 */
public class Customer {

    private int id;
    private String name;
    private String email;
    private Address address;
    private final List<Integer> accountIds = new ArrayList<>();
    private final List<Beneficiary> beneficiaries = new ArrayList<>();

    /**
     * Lazily loaded accounts resolved from account identifiers.
     */
    private LazyList<Account> accountsLazy;

    public Customer(int id, String name, String email, Address address) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.address = address;
    }

    public void addAccountId(int id) { accountIds.add(id); }

    public void addBeneficiary(Beneficiary b) { beneficiaries.add(b); }

    /**
     * Saves or updates a beneficiary entry by identifier.
     */
    public void saveBeneficiary(Beneficiary b) {
        Objects.requireNonNull(b, "beneficiary");

        for (int i = 0; i < beneficiaries.size(); i++) {
            Beneficiary existing = beneficiaries.get(i);
            if (existing.id() == b.id()) {
                beneficiaries.set(i, b);
                return;
            }
        }

        beneficiaries.add(b);
    }

    /**
     * Inserts or updates a beneficiary based on its identifier.
     */
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

    /**
     * Attaches lazy account resolution for this customer.
     */
    public void attachAccounts(LazyList<Account> accounts) {
        this.accountsLazy = accounts;
    }

    /**
     * Lazily resolves all accounts for this customer.
     */
    public List<Account> accounts() {
        return (accountsLazy != null) ? accountsLazy.getAll() : List.of();
    }
}
