package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.exceptions.InsufficientFundsException;
import cz.vsb.minibank.domain.exceptions.InvalidAmountException;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;

import java.util.Objects;

/**
 * Bank account aggregate with balance, daily ceiling and its own soft authorization tier.
 *
 * It no longer carries a list of its outgoing transfers. Nothing in production behaviour read
 * that list - every real "transfers of this account" question is answered by
 * TransferRepository.bySourceAccount, which is indexed - and the SQL backend has always
 * discarded it, so the two backends modelled the same aggregate differently and a JSON-only
 * test asserted an invariant SQL could not hold.
 */
public class Account {

    private int id;
    private IBAN iban;
    private Money balance;
    private Money dailyLimit;

    /**
     * This account's own soft authorization tier, or null to use the bank-wide one.
     *
     * Nullable rather than defaulted, because "this account has no opinion" and "this account
     * asks for authorization above zero" are different rules and a Money cannot say both.
     */
    private Money softDailyThreshold;

    /**
     * The version the store holds for this row, or 0 for an account no store has seen.
     *
     * The one persistence concern on this aggregate, and it is here rather than in a side map
     * because the identity map already makes this instance the single place a transaction's
     * view of the row lives. Only SqlAccountRepository touches it. On the JSON backend it stays
     * 0 forever and nothing reads it: JsonUnitOfWork holds the store lock from its constructor
     * to commit, so a JSON transaction's read and write cannot be interleaved and there is no
     * stale write for a version to catch.
     */
    private int version;

    public Account(int id, IBAN iban, Money balance, Money dailyLimit) {
        this(id, iban, balance, dailyLimit, null);
    }

    /**
     * @param softDailyThreshold this account's own soft authorization tier, or null to use the
     *                           bank-wide one. The four-argument constructor is kept because
     *                           most call sites have no opinion about a soft tier and adding a
     *                           fifth argument to all of them would be churn that says nothing.
     */
    public Account(int id, IBAN iban, Money balance, Money dailyLimit, Money softDailyThreshold) {
        this.id = id;
        this.iban = Objects.requireNonNull(iban);
        this.balance = Objects.requireNonNull(balance);
        this.dailyLimit = Objects.requireNonNull(dailyLimit);
        this.softDailyThreshold = softDailyThreshold;
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
     * Credits the account with an incoming amount.
     *
     * No fee argument: the fee is charged to the sender in {@link #debit} and is not taken a
     * second time here. The daily limit is not consulted either, because it caps what leaves
     * an account, not what arrives.
     *
     * @throws InvalidAmountException when the amount is not positive
     */
    public void credit(Money amount) {
        if (!amount.isPositive()) {
            throw new InvalidAmountException("Cannot credit " + amount);
        }
        this.balance = this.balance.plus(amount);
    }

    /**
     * Records the version the store holds for this account.
     *
     * Two callers, both in SqlAccountRepository: once when a row is read, and once after a
     * guarded write reports the version it left behind. The second call is what lets the same
     * account be saved more than once in one unit of work - DemoScenario saves the primary
     * account three times - without the second write conflicting with the first.
     *
     * The invariant a future retry must respect: once a save has executed, this number is the
     * version the store holds only while that transaction is still going to commit. If the
     * transaction is rolled back, the row goes back to what it was and this instance is left
     * one ahead of it, so the instance is unusable. Nothing reuses one today - SqlUnitOfWork's
     * cleanup clears the identity map and every service rethrows - but a retry that re-ran a
     * use case against the same Account object would write a stale balance over the winner's,
     * which is the very lost update the version exists to refuse.
     */
    public void hydrateVersion(int version) {
        this.version = version;
    }

    public int id() { return id; }
    public IBAN iban() { return iban; }
    public Money balance() { return balance; }
    public Money dailyLimit() { return dailyLimit; }

    /** This account's own soft authorization tier, or null when it uses the bank-wide one. */
    public Money softDailyThreshold() { return softDailyThreshold; }

    public int version() { return version; }
}
