package cz.vsb.minibank.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import cz.vsb.minibank.domain.lazy.LazyList;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.value.Money;

/**
 * Customer aggregate with accounts, beneficiaries, and the two limits on one day's spending.
 */
public class Customer {

    private int id;
    private String name;
    private String email;
    private Address address;
    private final List<Integer> accountIds = new ArrayList<>();
    private final List<Beneficiary> beneficiaries = new ArrayList<>();

    /**
     * The hard ceiling on what may leave this customer in one day, fees excluded.
     *
     * On the customer and not on the account, which is where it used to be. A ceiling per account
     * is a ceiling somebody with two accounts does not have: they pay half out of each and spend
     * the sum of two allowances, and the second half can even be paid by first moving money
     * between their own accounts, so a single account's balance never bounded it either.
     *
     * What the day's total measures follows from that: everything sent out of any account this
     * customer holds, minus what only moved between those accounts, because money that lands on
     * another account of the same customer has not left them. Totalling both accounts without
     * that subtraction would count an internal move twice - once leaving, once arriving - and let
     * the ceiling be inflated by shuffling money in place.
     */
    private Money dailyLimit;

    /**
     * This customer's own soft authorization tier, or null to use the bank-wide one.
     *
     * Nullable rather than defaulted, because "this customer has no opinion" and "this customer
     * asks for authorization above zero" are different rules and a Money cannot say both.
     */
    private Money softDailyThreshold;

    /**
     * Lazily loaded accounts resolved from account identifiers.
     */
    private LazyList<Account> accountsLazy;

    /**
     * @param dailyLimit the hard ceiling on one day's outflow; required. The four-argument
     *                   constructor is gone rather than kept as a convenience: a customer with
     *                   no ceiling is a customer nothing bounds, and every store that reads one
     *                   has the column to fill it from
     */
    public Customer(int id, String name, String email, Address address, Money dailyLimit) {
        this(id, name, email, address, dailyLimit, null);
    }

    /**
     * @param softDailyThreshold this customer's own soft authorization tier, or null to use the
     *                           bank-wide one. The five-argument constructor is kept because most
     *                           call sites have no opinion about a soft tier and adding a sixth
     *                           argument to all of them would be churn that says nothing
     */
    public Customer(int id, String name, String email, Address address, Money dailyLimit,
                    Money softDailyThreshold) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.address = address;
        this.dailyLimit = Objects.requireNonNull(dailyLimit, "dailyLimit");
        this.softDailyThreshold = softDailyThreshold;
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
    public Money dailyLimit() { return dailyLimit; }

    /** This customer's own soft authorization tier, or null when they use the bank-wide one. */
    public Money softDailyThreshold() { return softDailyThreshold; }

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
