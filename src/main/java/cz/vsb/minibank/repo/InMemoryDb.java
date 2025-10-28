package cz.vsb.minibank.repo;


import cz.vsb.minibank.model.*;


import java.util.*;


public class InMemoryDb {
    public List<Customer> customers = new ArrayList<>();
    public List<Account> accounts = new ArrayList<>();
    public List<Beneficiary> beneficiaries = new ArrayList<>();
    public List<Transfer> transfers = new ArrayList<>();


    private int customerSeq = 1, accountSeq = 100, beneficiarySeq = 10, transferSeq = 5000;


    public int nextCustomerId() { return customerSeq++; }
    public int nextAccountId() { return accountSeq++; }
    public int nextBeneficiaryId() { return beneficiarySeq++; }
    public int nextTransferId() { return transferSeq++; }


    public Optional<Customer> findCustomer(int id) {
        return customers.stream().filter(c -> c.id == id).findFirst();
    }
    public Optional<Account> findAccount(int id) {
        return accounts.stream().filter(a -> a.id == id).findFirst();
    }
    public Optional<Beneficiary> findBeneficiary(int id) {
        return beneficiaries.stream().filter(b -> b.id == id).findFirst();
    }


    public boolean ibanExists(String iban) {
        return accounts.stream().anyMatch(a -> a.iban.equalsIgnoreCase(iban))
                || beneficiaries.stream().anyMatch(b -> b.iban.equalsIgnoreCase(iban));
    }
}