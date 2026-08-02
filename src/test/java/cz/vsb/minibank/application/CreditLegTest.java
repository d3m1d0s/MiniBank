package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FeePolicy;
import cz.vsb.minibank.domain.RuleBasedRiskService;
import cz.vsb.minibank.domain.SimpleFeePolicy;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.ZeroFeePolicy;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A12: a payment to an IBAN this bank holds credits that account in the same unit of work as
 * the debit.
 *
 * Before the credit leg the money was destroyed - one measured session took system money from
 * 30 000.00 to 6 454.97 - so the assertions that matter here are about the books rather than
 * about one balance. Every case sums every balance in the store before and after and states
 * exactly what the difference is allowed to be.
 */
class CreditLegTest {

    private static final int PAYER_CUSTOMER_ID = 1;
    private static final int PAYEE_CUSTOMER_ID = 2;
    private static final int PAYER_ACCOUNT_ID = 100;
    private static final int PAYEE_ACCOUNT_ID = 101;

    private static final String PAYER_IBAN = "CZ6508000000192000145399";
    private static final String PAYEE_IBAN = "CZ4308000000192000145407";
    /** Held by no account of this bank, so it is what "external" means here. */
    private static final String OUTSIDE_IBAN = "CZ2001000000000012345678";

    private static final Money PAYER_OPENING = Money.czk(25_000);
    private static final Money PAYEE_OPENING = Money.czk(5_000);
    private static final Money OPENING_SYSTEM_MONEY = Money.czk(30_000);

    /** Settles at creation: not above the 5 000 untrusted threshold and not above the limit. */
    private static final double SETTLES_NOW = 5_000;
    /** Above the untrusted threshold, so it waits for the customer's OTP. */
    private static final double NEEDS_AUTH = 6_000;
    /** Above the fraud-alert threshold as well, so the analyst queue gets a row. */
    private static final double RAISES_ALERT = 12_000;

    @TempDir
    Path tempDir;

    private JsonDataStore store;
    private AccountRepository accounts;
    private TransferRepository transfers;
    private CustomerRepository customers;
    private cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory uowFactory;

    private TransferApplicationService service;
    private FraudApplicationService fraudService;
    private FakePaymentNetworkGateway gateway;
    private FeePolicy feePolicy;

    @BeforeEach
    void setUp() {
        buildWith(new SimpleFeePolicy());
    }

    /**
     * The whole fixture, parameterised by fee policy so the same scenario can be asserted with
     * a fee that is charged and with one that is not.
     */
    private void buildWith(FeePolicy policy) {
        Bootstrap infra = new Bootstrap(tempDir.resolve("data.json").toString());
        store = infra.store;
        accounts = infra.accounts;
        transfers = infra.transfers;
        customers = infra.customers;
        uowFactory = infra.uowFactory;
        feePolicy = policy;

        Customer payer = new Customer(PAYER_CUSTOMER_ID, "Payer", "payer@example.com",
                new Address("Test Street 1", "Ostrava"));
        payer.addAccountId(PAYER_ACCOUNT_ID);
        customers.save(payer);

        Customer payee = new Customer(PAYEE_CUSTOMER_ID, "Payee", "payee@example.com",
                new Address("Test Street 2", "Ostrava"));
        payee.addAccountId(PAYEE_ACCOUNT_ID);
        customers.save(payee);

        accounts.save(new Account(PAYER_ACCOUNT_ID, new IBAN(PAYER_IBAN),
                PAYER_OPENING, Money.czk(100_000)));
        accounts.save(new Account(PAYEE_ACCOUNT_ID, new IBAN(PAYEE_IBAN),
                PAYEE_OPENING, Money.czk(100_000)));

        gateway = new FakePaymentNetworkGateway();
        BootstrapServices services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts,
                policy, new RuleBasedRiskService(), new FixedOtpValidator(), gateway,
                infra.uowFactory);
        service = services.transferService;
        fraudService = services.fraudService;
    }

    /**
     * Every balance in the store, not a hand-picked pair. If a future fixture opens a third
     * account, this still sums it.
     */
    private Money systemMoney() {
        return store.read(bundle -> bundle.accounts.stream()
                .map(a -> Money.czk(a.balance))
                .reduce(Money.czk(0), Money::plus));
    }

    private Money balanceOf(int accountId) {
        return accounts.byId(accountId).orElseThrow().balance();
    }

    private Money fee(double amount) {
        return feePolicy.compute(Money.czk(amount));
    }

    // ---------------------------------------------------------------------
    // The books
    // ---------------------------------------------------------------------

    /**
     * The one assertion A12 exists for: an in-bank payment moves the amount instead of
     * destroying it, and the only money that leaves the system is the fee.
     */
    @Test
    void anInBankTransferConservesEverythingExceptTheFee() {
        assertEquals(OPENING_SYSTEM_MONEY, systemMoney(), "fixture precondition");

        service.submitPaymentToIban(PAYER_CUSTOMER_ID, PAYER_ACCOUNT_ID, PAYEE_IBAN, SETTLES_NOW, "");

        Money charged = fee(SETTLES_NOW);
        assertTrue(charged.isPositive(), "this case is only interesting with a fee to lose");

        assertEquals(PAYER_OPENING.minus(Money.czk(SETTLES_NOW)).minus(charged),
                balanceOf(PAYER_ACCOUNT_ID), "the sender pays the amount and the fee");
        assertEquals(PAYEE_OPENING.plus(Money.czk(SETTLES_NOW)),
                balanceOf(PAYEE_ACCOUNT_ID), "the destination receives the amount");

        assertEquals(OPENING_SYSTEM_MONEY.minus(charged), systemMoney(),
                "system money may fall by the fee and by nothing else");
        assertTrue(gateway.sentTransfers().isEmpty(),
                "a payment that never leaves the bank must not be offered to the network too");
    }

    /**
     * The same movement with no fee at all, so the sum before and after is identical. This is
     * the case where "conserved" means conserved rather than "conserved less something".
     */
    @Test
    void underAZeroFeePolicyAnInBankTransferConservesSystemMoneyExactly() {
        buildWith(new ZeroFeePolicy());

        Money before = systemMoney();
        service.submitPaymentToIban(PAYER_CUSTOMER_ID, PAYER_ACCOUNT_ID, PAYEE_IBAN, SETTLES_NOW, "");

        assertEquals(Money.czk(0), fee(SETTLES_NOW));
        assertEquals(before, systemMoney(), "with no fee nothing may leave the system");
        assertEquals(PAYER_OPENING.minus(Money.czk(SETTLES_NOW)), balanceOf(PAYER_ACCOUNT_ID));
        assertEquals(PAYEE_OPENING.plus(Money.czk(SETTLES_NOW)), balanceOf(PAYEE_ACCOUNT_ID));
    }

    /**
     * The other half of the routing rule, unchanged by A12: a payment to an IBAN this bank
     * does not hold still goes to the gateway, and the amount really does leave.
     */
    @Test
    void anExternalTransferStillReachesTheGatewayAndStillLeavesTheSystem() {
        int id = service.submitPaymentToIban(
                PAYER_CUSTOMER_ID, PAYER_ACCOUNT_ID, OUTSIDE_IBAN, SETTLES_NOW, "");

        Money charged = fee(SETTLES_NOW);

        assertEquals(1, gateway.sentTransfers().size(), "the network stub must have been called");
        assertEquals(id, gateway.sentTransfers().get(0).id());

        assertEquals(PAYER_OPENING.minus(Money.czk(SETTLES_NOW)).minus(charged),
                balanceOf(PAYER_ACCOUNT_ID));
        assertEquals(PAYEE_OPENING, balanceOf(PAYEE_ACCOUNT_ID),
                "no account of this bank may be credited for a payment that left it");
        assertEquals(OPENING_SYSTEM_MONEY.minus(Money.czk(SETTLES_NOW)).minus(charged),
                systemMoney(), "amount and fee both leave the system on an external payment");
    }

    // ---------------------------------------------------------------------
    // Nothing is credited before it is authorized, and nothing after a refusal
    // ---------------------------------------------------------------------

    @Test
    void aTransferWaitingForAuthorizationCreditsNobodyUntilItIsAuthorized() {
        int id = service.submitPaymentToIban(
                PAYER_CUSTOMER_ID, PAYER_ACCOUNT_ID, PAYEE_IBAN, NEEDS_AUTH, "");

        assertEquals(TransferStatus.WAITING_AUTH, transfers.byId(id).orElseThrow().status());
        assertEquals(PAYER_OPENING, balanceOf(PAYER_ACCOUNT_ID), "the sender still holds it");
        assertEquals(PAYEE_OPENING, balanceOf(PAYEE_ACCOUNT_ID),
                "crediting before authorization would create money");
        assertEquals(OPENING_SYSTEM_MONEY, systemMoney());

        service.authorizePayment(PAYER_CUSTOMER_ID, id, FixedOtpValidator.DEMO_OTP);

        Money charged = fee(NEEDS_AUTH);
        assertEquals(TransferStatus.SENT, transfers.byId(id).orElseThrow().status());
        assertEquals(PAYER_OPENING.minus(Money.czk(NEEDS_AUTH)).minus(charged),
                balanceOf(PAYER_ACCOUNT_ID));
        assertEquals(PAYEE_OPENING.plus(Money.czk(NEEDS_AUTH)), balanceOf(PAYEE_ACCOUNT_ID));
        assertEquals(OPENING_SYSTEM_MONEY.minus(charged), systemMoney());
        assertTrue(gateway.sentTransfers().isEmpty(), "still in bank, still not the network's");
    }

    @Test
    void aCancelledTransferNeverCreditsAnybody() {
        int id = service.submitPaymentToIban(
                PAYER_CUSTOMER_ID, PAYER_ACCOUNT_ID, PAYEE_IBAN, NEEDS_AUTH, "");
        service.cancelPayment(PAYER_CUSTOMER_ID, id);

        assertEquals(TransferStatus.DECLINED, transfers.byId(id).orElseThrow().status());
        assertEquals(PAYER_OPENING, balanceOf(PAYER_ACCOUNT_ID));
        assertEquals(PAYEE_OPENING, balanceOf(PAYEE_ACCOUNT_ID));
        assertEquals(OPENING_SYSTEM_MONEY, systemMoney(), "a cancelled payment moves nothing");
        assertTrue(gateway.sentTransfers().isEmpty());
    }

    @Test
    void aTransferDeclinedByTheFraudDeskNeverCreditsAnybody() {
        int id = service.submitPaymentToIban(
                PAYER_CUSTOMER_ID, PAYER_ACCOUNT_ID, PAYEE_IBAN, RAISES_ALERT, "");
        assertEquals(TransferStatus.WAITING_AUTH, transfers.byId(id).orElseThrow().status());

        fraudService.decline(id, "looks wrong");

        assertEquals(TransferStatus.DECLINED, transfers.byId(id).orElseThrow().status());
        assertEquals(PAYER_OPENING, balanceOf(PAYER_ACCOUNT_ID));
        assertEquals(PAYEE_OPENING, balanceOf(PAYEE_ACCOUNT_ID));
        assertEquals(OPENING_SYSTEM_MONEY, systemMoney());
    }

    // ---------------------------------------------------------------------
    // Identity map: one instance per row per unit of work
    // ---------------------------------------------------------------------

    /**
     * The regression guard for hazard one. Against the old byIban - build an Account from the
     * row and put it unconditionally - this fails on the very first assertion, because the two
     * lookups return two objects for one row.
     */
    @Test
    void byIdAndByIbanReturnOneInstanceForOneRowInsideAUnitOfWork() {
        UnitOfWork uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            Account byId = accounts.byId(PAYEE_ACCOUNT_ID).orElseThrow();
            Account byIban = accounts.byIban(new IBAN(PAYEE_IBAN)).orElseThrow();

            assertSame(byId, byIban, "one row must mean one Account per unit of work");

            // And in the other order, because the probe has to work whichever key arrives first.
            assertSame(byIban, accounts.byId(PAYEE_ACCOUNT_ID).orElseThrow());
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /**
     * The consequence that made hazard one worth fixing, asserted as behaviour rather than as
     * object identity. Against the old byIban the second lookup hands back a pre-mutation copy
     * and saving it writes the stale balance, so the credit is silently dropped and this
     * fails with 5 000 where 6 000 was expected.
     */
    @Test
    void aMutationMadeBeforeAByIbanLookupSurvivesTheCommit() {
        UnitOfWork uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            accounts.byId(PAYEE_ACCOUNT_ID).orElseThrow().credit(Money.czk(1_000));

            Account resolved = accounts.byIban(new IBAN(PAYEE_IBAN)).orElseThrow();
            accounts.save(resolved);

            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }

        assertEquals(PAYEE_OPENING.plus(Money.czk(1_000)), balanceOf(PAYEE_ACCOUNT_ID),
                "a lookup by a second key must not write back a stale balance");
    }

    /**
     * The other direction of the same invariant: both backends defer their writes, so an
     * account opened in this unit of work has no row to find. Answering "not ours" for it
     * would route a payment to an account of this bank out to the payment network, which is
     * A12 all over again. Against a store-only byIban this returns empty and fails.
     */
    @Test
    void anAccountOpenedInThisUnitOfWorkIsAlreadyInBank() {
        String freshIban = "CZ2108000000192000145415";

        UnitOfWork uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            Account opened = new Account(300, new IBAN(freshIban),
                    Money.czk(1_000), Money.czk(10_000));
            accounts.save(opened);

            Account found = accounts.inBankByIban(freshIban).orElseThrow(
                    () -> new AssertionError("an account this transaction just opened is ours"));
            assertSame(opened, found);
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /**
     * accounts.iban is UNIQUE in the SQL schema and nothing enforced it on the JSON side. That
     * was harmless while byIban only answered "is the demo seeded"; it now decides who
     * receives money, so an ambiguous store is refused instead of resolved by list order.
     */
    @Test
    void twoJsonAccountsOnOneIbanAreRefusedRatherThanGuessed() {
        accounts.save(new Account(300, new IBAN(PAYEE_IBAN), Money.czk(1), Money.czk(1)));

        assertThrows(DataIntegrityException.class, () -> accounts.byIban(new IBAN(PAYEE_IBAN)));
    }

    // ---------------------------------------------------------------------
    // The domain's own guards on a stored row that contradicts itself
    // ---------------------------------------------------------------------

    @Test
    void sendRefusesToCreditItsOwnSourceAccount() {
        Account source = accounts.byId(PAYER_ACCOUNT_ID).orElseThrow();
        Transfer t = new Transfer(900, PAYER_ACCOUNT_ID, null, PAYER_IBAN, Money.czk(100), "CZK");

        assertThrows(DataIntegrityException.class, () -> t.send(source, source, feePolicy));
        assertEquals(PAYER_OPENING, source.balance(), "the refusal must come before the debit");
    }

    @Test
    void sendRefusesAnAccountThatIsNotTheOneTheTransferNames() {
        Account source = accounts.byId(PAYER_ACCOUNT_ID).orElseThrow();
        Account wrong = accounts.byId(PAYEE_ACCOUNT_ID).orElseThrow();
        Transfer t = new Transfer(901, PAYER_ACCOUNT_ID, null, OUTSIDE_IBAN, Money.czk(100), "CZK");

        assertThrows(DataIntegrityException.class, () -> t.send(source, wrong, feePolicy));
        assertEquals(PAYER_OPENING, source.balance());
        assertEquals(PAYEE_OPENING, wrong.balance());
    }

    @Test
    void sendRefusesASourceThatIsNotTheAccountTheTransferDebits() {
        Account notTheSource = accounts.byId(PAYEE_ACCOUNT_ID).orElseThrow();
        Transfer t = new Transfer(902, PAYER_ACCOUNT_ID, null, OUTSIDE_IBAN, Money.czk(100), "CZK");

        assertThrows(DataIntegrityException.class, () -> t.send(notTheSource, null, feePolicy));
        assertEquals(PAYEE_OPENING, notTheSource.balance());
    }

    /**
     * The destination check compares IBANs, not strings. A snapshot carrying the spacing and
     * case a customer typed - or written before IBAN validation existed - names the same
     * account, and refusing it would turn a payment that used to settle into a 500 the
     * customer can never clear.
     */
    @Test
    void aDenormalizedTargetSnapshotStillMatchesTheAccountItNames() {
        Account source = accounts.byId(PAYER_ACCOUNT_ID).orElseThrow();
        Account destination = accounts.byId(PAYEE_ACCOUNT_ID).orElseThrow();
        Transfer t = new Transfer(903, PAYER_ACCOUNT_ID, null,
                "cz43 0800 0000 1920 0014 5407", Money.czk(100), "CZK");

        assertDoesNotThrow(() -> t.send(source, destination, feePolicy));
        assertEquals(PAYEE_OPENING.plus(Money.czk(100)), destination.balance());
    }
}
