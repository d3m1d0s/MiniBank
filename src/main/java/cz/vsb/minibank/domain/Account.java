package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.exceptions.InsufficientFundsException;
import cz.vsb.minibank.domain.exceptions.InvalidAmountException;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bank account aggregate with balance, daily limit and outgoing transfers.
 */
public class Account {

    private int id;
    private IBAN iban;
    private Money balance;
    private Money dailyLimit;
    private final List<Integer> transferIds = new ArrayList<>();

    public Account(int id, IBAN iban, Money balance, Money dailyLimit) {
        this.id = id;
        this.iban = Objects.requireNonNull(iban);
        this.balance = Objects.requireNonNull(balance);
        this.dailyLimit = Objects.requireNonNull(dailyLimit);
    }

    /**
     * Returns true when amount and fee are debitable values and the balance covers them.
     */
    public boolean canDebit(Money amount, Money fee) {
        // A non-positive amount or a negative fee makes the comparison below trivially
        // true, which is what let a debit add to the balance instead of reducing it.
        if (!amount.isPositive() || fee.isNegative()) {
            return false;
        }
        Money total = amount.plus(fee);
        return balance.gte(total);
    }

    /**
     * Debits the account by amount plus fee.
     *
     * @throws InvalidAmountException when the amount is not positive or the fee is negative
     * @throws InsufficientFundsException when the balance does not cover amount and fee
     */
    public void debit(Money amount, Money fee) {
        // Checked before canDebit so that a bad amount is not reported as missing funds.
        if (!amount.isPositive() || fee.isNegative()) {
            throw new InvalidAmountException("Cannot debit " + amount + " with fee " + fee);
        }

        Money total = amount.plus(fee);
        if (!canDebit(amount, fee)) {
            throw new InsufficientFundsException("Insufficient funds");
        }
        this.balance = this.balance.minus(total);
    }

    /**
     * Registers an outgoing transfer identifier with this account.
     */
    public void registerTransfer(int transferId) {
        transferIds.add(transferId);
    }

    public int id() { return id; }
    public IBAN iban() { return iban; }
    public Money balance() { return balance; }
    public Money dailyLimit() { return dailyLimit; }
    public List<Integer> transferIds() { return transferIds; }
}
