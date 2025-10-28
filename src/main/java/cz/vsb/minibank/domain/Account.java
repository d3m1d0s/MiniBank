package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.exceptions.InsufficientFundsException;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Account {
    private int id;
    private IBAN iban;
    private Money balance;
    private Money dailyLimit;
    private final List<Integer> transferIds = new ArrayList<>();

    public Account(int id, IBAN iban, Money balance, Money dailyLimit) {
        this.id = id; this.iban = Objects.requireNonNull(iban);
        this.balance = Objects.requireNonNull(balance);
        this.dailyLimit = Objects.requireNonNull(dailyLimit);
    }

    public boolean canDebit(Money amount, Money fee) {
        Money total = amount.plus(fee);
        return balance.gte(total);
    }

    public void debit(Money amount, Money fee) {
        Money total = amount.plus(fee);
        if (!canDebit(amount, fee)) throw new InsufficientFundsException("Insufficient funds");
        this.balance = this.balance.minus(total);
    }

    public void registerTransfer(int transferId) { transferIds.add(transferId); }

    public int id() { return id; }
    public IBAN iban() { return iban; }
    public Money balance() { return balance; }
    public Money dailyLimit() { return dailyLimit; }
    public List<Integer> transferIds() { return transferIds; }
}