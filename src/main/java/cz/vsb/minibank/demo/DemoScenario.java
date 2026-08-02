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
    private static final Money PRIMARY_DAILY_LIMIT = Money.czk(15_000);
    private static final Money SECONDARY_OPENING_BALANCE = Money.czk(5_000);
    private static final Money SECONDARY_DAILY_LIMIT = Money.czk(8_000);

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

        Account primary = openAccount(customer, PRIMARY_IBAN, PRIMARY_OPENING_BALANCE, PRIMARY_DAILY_LIMIT);
        openAccount(customer, SECONDARY_IBAN, SECONDARY_OPENING_BALANCE, SECONDARY_DAILY_LIMIT);
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

    private Account openAccount(Customer owner, IBAN iban, Money balance, Money dailyLimit) {
        Account account = new Account(accounts.nextId(), iban, balance, dailyLimit);
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
        transfer.send(source, feePolicy);
        transfers.add(transfer);
        source.registerTransfer(transfer.id());
        accounts.save(source);
    }

    /**
     * A payment large enough to trip the fraud rules, so the analyst queue and
     * the customer's pending-authorization list are both non-empty.
     */
    private void flagTransfer(Account source, Beneficiary target) {
        Transfer transfer = newTransfer(source, target, FLAGGED_AMOUNT);
        transfer.requestAuthorization(new CardPayment(transfer.amount(), "****0000"));
        transfers.add(transfer);
        source.registerTransfer(transfer.id());
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
