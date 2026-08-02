package cz.vsb.minibank.demo;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;

import java.util.Objects;
import java.util.Optional;

/**
 * The single definition of the demo dataset, shared by every entry point and
 * both persistence backends.
 * <p>
 * Balances and fees are produced by executing the domain rather than being
 * written down, so they stay consistent with whatever {@link FeePolicy} is in
 * force. The primary account IBAN is the only demo identifier that exists as a
 * constant; every technical id comes from the repositories.
 */
public final class DemoScenario {

    /**
     * Natural key of the demo dataset. Because accounts.iban is unique in the
     * SQL schema, presence of this IBAN is both the seeded-already check and
     * the constraint that would reject a second seed.
     */
    public static final IBAN PRIMARY_IBAN = new IBAN("CZ6508000000192000145399");
    public static final IBAN SECONDARY_IBAN = new IBAN("CZ4308000000192000145407");
    public static final IBAN TRUSTED_BENEFICIARY_IBAN = new IBAN("CZ2108000000192000145415");
    public static final IBAN UNTRUSTED_BENEFICIARY_IBAN = new IBAN("CZ9608000000192000145423");

    private static final String CUSTOMER_NAME = "Alice Demo";
    private static final String CUSTOMER_EMAIL = "alice@example.com";

    private static final Money PRIMARY_OPENING_BALANCE = Money.czk(25_000);

    /**
     * The hard ceiling on one day's outflow, above RuleBasedRiskService's 15 000 soft threshold
     * so the demo can cross that one - which only asks for an authorization the script then
     * supplies - without being refused outright. The peak cumulative attempt across DemoRunner
     * is 24 700, so this clears it.
     */
    private static final Money PRIMARY_DAILY_LIMIT = Money.czk(40_000);
    private static final Money SECONDARY_OPENING_BALANCE = Money.czk(5_000);

    private static final Money SECONDARY_DAILY_LIMIT = Money.czk(8_000);

    /**
     * Strictly inside the secondary account's own 8 000 ceiling, so this account really does
     * have two tiers: a day total up to 3 000 settles on the spot, above it asks the customer to
     * authorize, and above 8 000 is refused. Against the bank-wide 15 000 it had one tier,
     * because every total that could have reached 15 000 had already been refused at 8 000 - and
     * with an opening balance of 5 000 nothing on this account could ever have got near it
     * anyway. That was the standing demonstration that the soft tier was a bank-wide constant
     * pretending to be a per-account rule.
     *
     * The primary account deliberately gets no override and rides the bank-wide 15 000, which is
     * the threshold DemoRunner's script is written against. One account on the default and one
     * with an override is what makes the dataset show both cases.
     */
    public static final Money SECONDARY_SOFT_THRESHOLD = Money.czk(3_000);

    /** Below the authorization threshold, so it settles immediately and forms the history. */
    private static final Money SETTLED_AMOUNT = Money.czk(1_500);

    /** Above the fraud-alert threshold, so it waits for authorization and raises an alert. */
    private static final Money FLAGGED_AMOUNT = Money.czk(12_000);

    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final FraudAlertRepository alerts;
    private final UnitOfWorkFactory uowFactory;
    private final FeePolicy feePolicy;

    public DemoScenario(CustomerRepository customers,
                        AccountRepository accounts,
                        TransferRepository transfers,
                        FraudAlertRepository alerts,
                        UnitOfWorkFactory uowFactory,
                        FeePolicy feePolicy) {
        this.customers = Objects.requireNonNull(customers);
        this.accounts = Objects.requireNonNull(accounts);
        this.transfers = Objects.requireNonNull(transfers);
        this.alerts = Objects.requireNonNull(alerts);
        this.uowFactory = Objects.requireNonNull(uowFactory);
        this.feePolicy = Objects.requireNonNull(feePolicy);
    }

    /**
     * Creates the demo dataset if it is absent and returns the demo customer id.
     * Repeated calls are no-ops that return the same id.
     */
    public int seed() {
        UnitOfWork uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            Optional<Account> alreadySeeded = accounts.byIban(PRIMARY_IBAN);
            if (alreadySeeded.isPresent()) {
                int ownerId = customers.byAccountId(alreadySeeded.get().id())
                        .orElseThrow(() -> new IllegalStateException(
                                "Demo account " + PRIMARY_IBAN.value() + " exists but belongs to no customer"))
                        .id();
                uow.rollback();
                return ownerId;
            }

            int customerId = create(uow);
            uow.commit();
            return customerId;
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    private int create(UnitOfWork uow) {
        int customerId = customers.nextId();
        Customer customer = new Customer(
                customerId,
                CUSTOMER_NAME,
                CUSTOMER_EMAIL,
                new Address("Hlavni 1", "Ostrava"));
        customers.save(customer);

        // No soft-tier override: the primary rides the bank-wide 15 000, which is what
        // DemoRunner's first two payments - 6 000 then 12 000 - are written to cross.
        Account primary = openAccount(customer, PRIMARY_IBAN, PRIMARY_OPENING_BALANCE, PRIMARY_DAILY_LIMIT);
        openAccount(customer, SECONDARY_IBAN, SECONDARY_OPENING_BALANCE, SECONDARY_DAILY_LIMIT,
                SECONDARY_SOFT_THRESHOLD);
        // Not a redundant repeat of the save above. In SQL mode this is what writes
        // accounts.customer_id: SqlAccountRepository leaves the column NULL and only
        // SqlCustomerRepository.upsertCustomer assigns it, from Customer.accountIds(), which
        // is empty on the first save. That column is the sole record of ownership and is what
        // OwnershipGuard reads, so dropping this line makes every money path 404 in SQL.
        customers.save(customer);

        addBeneficiary(customerId, "Bob Trusted", TRUSTED_BENEFICIARY_IBAN, true);
        Beneficiary risky = addBeneficiary(customerId, "Mallory Risky", UNTRUSTED_BENEFICIARY_IBAN, false);

        settleTransfer(primary, risky);
        flagTransfer(primary, risky);

        return customerId;
    }

    /** An account on the bank-wide soft tier. */
    private Account openAccount(Customer owner, IBAN iban, Money balance, Money dailyLimit) {
        return openAccount(owner, iban, balance, dailyLimit, null);
    }

    private Account openAccount(Customer owner, IBAN iban, Money balance, Money dailyLimit,
                                Money softDailyThreshold) {
        Account account = new Account(accounts.nextId(), iban, balance, dailyLimit, softDailyThreshold);
        accounts.save(account);
        owner.addAccountId(account.id());
        return account;
    }

    private Beneficiary addBeneficiary(int customerId, String name, IBAN iban, boolean trusted) {
        Beneficiary beneficiary = new Beneficiary(customers.nextBeneficiaryId(), name, iban, trusted);
        customers.saveBeneficiary(customerId, beneficiary);
        return beneficiary;
    }

    /**
     * A completed payment, so the demo opens with a non-empty history and a
     * balance that reflects the current fee policy.
     */
    private void settleTransfer(Account source, Beneficiary target) {
        Transfer transfer = newTransfer(source, target, SETTLED_AMOUNT);
        // The seed asks the same question the services ask instead of hard-coding null, so it
        // stays correct if the dataset ever opens an account at a beneficiary IBAN. The two
        // accounts above are created in this very unit of work and have no rows yet, which is
        // why inBankByIban consults the identity map before the store. Today the answer is
        // nothing: both beneficiary IBANs are beneficiaries and never accounts, so every
        // seeded transfer stays external and no demo balance moves.
        Account destination = accounts.inBankByIban(target.iban().value()).orElse(null);
        // Its own creation instant is the settlement instant: the seed orders and pays in one
        // step, so there is exactly one moment here and no second reading to disagree with.
        // This keeps the seed off the system clock a second time, like newTransfer does.
        transfer.send(source, destination, feePolicy, transfer.createdAt());
        transfers.add(transfer);
        accounts.saveBothInIdOrder(source, destination);
    }

    /**
     * A payment large enough to trip the fraud rules, so the analyst queue and the customer's
     * pending list are both non-empty.
     *
     * Held, not waiting: the seed has to produce the shape the service produces. Seeding it as
     * WAITING_AUTH would ship the exact bug the review gate closes - an alert sitting NEW in
     * the queue on a transfer the customer can confirm at will.
     */
    private void flagTransfer(Account source, Beneficiary target) {
        Transfer transfer = newTransfer(source, target, FLAGGED_AMOUNT);
        transfer.holdForReview(new CardPayment(transfer.amount(), "****0000"));
        transfers.add(transfer);
        accounts.save(source);

        alerts.add(new FraudAlert(
                alerts.nextId(),
                transfer.id(),
                "New beneficiary + high amount",
                80,
                null,
                null,
                null));
    }

    private Transfer newTransfer(Account source, Beneficiary target, Money amount) {
        return new Transfer(
                transfers.nextId(),
                source.id(),
                target.id(),
                target.iban().value(),
                amount,
                "CZK");
    }
}
