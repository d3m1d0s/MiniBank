package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.InvalidAmountException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.json.dto.JsonTransfer;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Payment amounts that cannot move money must be rejected before anything is persisted.
 * The headline case is a negative amount, which used to pass the balance check and turn
 * the debit into a credit.
 */
class TransferAmountValidationTest {

    private static final int CUSTOMER_ID = 1;
    private static final int ACCOUNT_ID = 101;
    private static final int BENEFICIARY_ID = 5001;
    private static final String TARGET_IBAN = "CZ6508000000192000145399";
    private static final Money OPENING_BALANCE = Money.czk(25_000);

    @TempDir
    Path tempDir;

    private TransferApplicationService service;
    private FakePaymentNetworkGateway gateway;
    private AccountRepository accounts;
    private TransferRepository transfers;

    @BeforeEach
    void setUp() {
        Bootstrap infra = new Bootstrap(tempDir.resolve("data.json").toString());
        accounts = infra.accounts;
        transfers = infra.transfers;

        CustomerRepository customers = infra.customers;
        Customer customer = new Customer(CUSTOMER_ID, "Amount Test", "amount@example.com",
                new Address("Test Street 1", "Ostrava"));
        customer.addAccountId(ACCOUNT_ID);
        customers.save(customer);

        accounts.save(new Account(ACCOUNT_ID, new IBAN("CZ5201000000199999999999"),
                OPENING_BALANCE, Money.czk(15_000)));

        customers.saveBeneficiary(CUSTOMER_ID,
                new Beneficiary(BENEFICIARY_ID, "Target", new IBAN(TARGET_IBAN), true));

        BootstrapServices services = new BootstrapServices(
                customers, accounts, transfers, infra.alerts, infra.uowFactory);
        service = services.transferService;
        gateway = (FakePaymentNetworkGateway) services.paymentGateway;
    }

    private void assertNothingHappened() {
        assertEquals(OPENING_BALANCE, accounts.byId(ACCOUNT_ID).orElseThrow().balance());
        assertTrue(transfers.bySourceAccount(ACCOUNT_ID).isEmpty());
        assertTrue(gateway.sentTransfers().isEmpty());
    }

    @Test
    void aNegativeAmountIsRejectedAndDoesNotCreditTheAccount() {
        assertThrows(InvalidAmountException.class,
                () -> service.submitPaymentToIban(CUSTOMER_ID, ACCOUNT_ID, TARGET_IBAN, -50_000, ""));

        assertNothingHappened();
    }

    @Test
    void aZeroAmountIsRejected() {
        assertThrows(InvalidAmountException.class,
                () -> service.submitPaymentToIban(CUSTOMER_ID, ACCOUNT_ID, TARGET_IBAN, 0, ""));

        assertNothingHappened();
    }

    @Test
    void anAmountBelowOneHellerIsRejected() {
        assertThrows(InvalidAmountException.class,
                () -> service.submitPaymentToIban(CUSTOMER_ID, ACCOUNT_ID, TARGET_IBAN, 0.001, ""));

        assertNothingHappened();
    }

    /**
     * 0.005 used to be rounded up and charged as 0.01, moving twice what was requested.
     */
    @Test
    void anAmountThatWouldBeRoundedUpIsRejected() {
        assertThrows(InvalidAmountException.class,
                () -> service.submitPaymentToIban(CUSTOMER_ID, ACCOUNT_ID, TARGET_IBAN, 0.005, ""));

        assertNothingHappened();
    }

    @Test
    void anAmountWithMoreThanTwoDecimalPlacesIsRejected() {
        assertThrows(InvalidAmountException.class,
                () -> service.submitPaymentToIban(CUSTOMER_ID, ACCOUNT_ID, TARGET_IBAN, 1.234, ""));

        assertNothingHappened();
    }

    @Test
    void aNonFiniteAmountIsRejected() {
        assertThrows(InvalidAmountException.class,
                () -> service.submitPaymentToIban(CUSTOMER_ID, ACCOUNT_ID, TARGET_IBAN, Double.NaN, ""));
        assertThrows(InvalidAmountException.class,
                () -> service.submitPaymentToIban(CUSTOMER_ID, ACCOUNT_ID, TARGET_IBAN,
                        Double.POSITIVE_INFINITY, ""));

        assertNothingHappened();
    }

    @Test
    void theBeneficiaryPathRejectsTheSameAmounts() {
        assertThrows(InvalidAmountException.class,
                () -> service.submitPaymentByBeneficiary(CUSTOMER_ID, ACCOUNT_ID, BENEFICIARY_ID, -1000, ""));
        assertThrows(InvalidAmountException.class,
                () -> service.submitPaymentByBeneficiary(CUSTOMER_ID, ACCOUNT_ID, BENEFICIARY_ID, 0, ""));

        assertNothingHappened();
    }

    /**
     * Guards against over-tightening: the smallest amount the currency can express still works.
     */
    @Test
    void theSmallestValidAmountStillMovesMoney() {
        int id = service.submitPaymentToIban(CUSTOMER_ID, ACCOUNT_ID, TARGET_IBAN, 0.01, "").transferId();

        Transfer stored = transfers.byId(id).orElseThrow();
        assertEquals(Money.czk(0.01), stored.amount());
        assertEquals(OPENING_BALANCE.minus(Money.czk(0.01)),
                accounts.byId(ACCOUNT_ID).orElseThrow().balance());
        assertEquals(1, gateway.sentTransfers().size());
    }

    /**
     * The constructor guard is unreachable from a request: Money.czkPayment rejects every
     * non-positive amount before a Transfer is built. So it is a corrupt-data guard, and
     * DataIntegrityException is what keeps it a 500 rather than telling the client its own
     * request was bad. The guard itself is unchanged.
     */
    @Test
    void aNonPositiveAmountIsRefusedByTheConstructorAsCorruptData() {
        assertThrows(DataIntegrityException.class,
                () -> new Transfer(1, ACCOUNT_ID, null, TARGET_IBAN, Money.czk(-1)));
        assertThrows(DataIntegrityException.class,
                () -> new Transfer(1, ACCOUNT_ID, null, TARGET_IBAN, Money.czk(0)));
    }

    @Test
    void debitRejectsANonPositiveAmountWithoutTouchingTheBalance() {
        Account account = new Account(1, new IBAN(TARGET_IBAN), Money.czk(100), Money.czk(1000));

        assertFalse(account.canDebit(Money.czk(-1000), Money.czk(0)));
        assertThrows(InvalidAmountException.class,
                () -> account.debit(Money.czk(-1000), Money.czk(0)));
        assertThrows(InvalidAmountException.class,
                () -> account.debit(Money.czk(0), Money.czk(0)));

        assertEquals(Money.czk(100), account.balance());
    }

    /**
     * The invariant deliberately also applies when a stored row is read back, so a row
     * written before the guard existed is refused rather than loaded. Do not relax the
     * constructor to make this pass.
     */
    @Test
    void aStoredRowWithANonPositiveAmountIsRejectedOnLoad() {
        JsonTransfer row = new JsonTransfer();
        row.id = 900;
        row.sourceAccountId = ACCOUNT_ID;
        row.targetIbanSnapshot = TARGET_IBAN;
        row.amount = new BigDecimal("-1000.00");
        row.currency = "CZK";
        row.status = "SENT";

        assertThrows(DataIntegrityException.class, () -> JsonMapper.toDomain(row));
    }

    /**
     * The neighbouring case, and the reason the stored amount stopped being a primitive. A
     * {@code double} field has no absent value: a row written without an amount deserialized to
     * 0.00 and was loaded as a transfer of nothing, which the positivity guard above would then
     * refuse for the wrong reason or, before that guard existed, accept outright. A reference
     * type can hold "no amount", so the row is refused for what is actually wrong with it.
     */
    @Test
    void aStoredRowWithNoAmountAtAllIsRefusedRatherThanReadAsZero() {
        JsonTransfer row = new JsonTransfer();
        row.id = 901;
        row.sourceAccountId = ACCOUNT_ID;
        row.targetIbanSnapshot = TARGET_IBAN;
        row.amount = null;
        row.currency = "CZK";
        row.status = "SENT";

        DataIntegrityException thrown =
                assertThrows(DataIntegrityException.class, () -> JsonMapper.toDomain(row));
        assertTrue(thrown.getMessage().contains("901"),
                "the refusal must name the row, so a corrupt store can be found");
    }

    /**
     * The same invariant on the currency. This bank keeps crowns, and a transfer records that in
     * one place now - the currency its {@code Money} carries - rather than in a Money and a
     * String beside it that nothing reconciled.
     *
     * Unreachable from a request for the same reason as the amount guard: both creation paths
     * build the amount through {@code Money.czkPayment}. So it is a corrupt-data guard and a 500,
     * not a 400.
     */
    @Test
    void aForeignAmountIsRefusedByTheConstructorAsCorruptData() {
        DataIntegrityException thrown = assertThrows(DataIntegrityException.class,
                () -> new Transfer(1, ACCOUNT_ID, null, TARGET_IBAN,
                        Money.of("EUR", new BigDecimal("100.00"))));

        assertTrue(thrown.getMessage().contains("EUR"),
                "the refusal must name what the amount actually was");
    }

    /**
     * The half of it that was a real divergence between the two backends rather than a
     * hypothetical.
     *
     * The SQL adapter has always rebuilt a stored row's amount in the currency the row names.
     * This one forced every stored amount to crowns, so a row saying EUR loaded as that many
     * real CZK and was debited as such, while the identical row on PostgreSQL came back as EUR.
     * One row, two answers, decided by which adapter read it. Now both rebuild it faithfully and
     * the constructor refuses what comes out.
     */
    @Test
    void aStoredRowInAnotherCurrencyIsRefusedRatherThanReadAsCrowns() {
        JsonTransfer row = new JsonTransfer();
        row.id = 902;
        row.sourceAccountId = ACCOUNT_ID;
        row.targetIbanSnapshot = TARGET_IBAN;
        row.amount = new BigDecimal("1000.00");
        row.currency = "EUR";
        row.status = "SENT";

        DataIntegrityException thrown =
                assertThrows(DataIntegrityException.class, () -> JsonMapper.toDomain(row));
        assertTrue(thrown.getMessage().contains("EUR"),
                "the refusal must name the currency the row claimed");
    }

    /**
     * The rule stated at the aggregate rather than at a loader, because both loaders pass through
     * it and only one of them can catch this first.
     *
     * On the JSON side an absent creation instant is refused while the row is still a DTO. On the
     * SQL side there is nothing to parse - a NULL column arrives as a null Instant - so this is
     * the only thing standing between a NULL {@code created_at} and a transfer dated at the
     * moment it was read.
     */
    @Test
    void hydratingATransferWithNoCreationInstantIsRefused() {
        Transfer t = new Transfer(910, ACCOUNT_ID, null, TARGET_IBAN, Money.czk(100));

        DataIntegrityException thrown = assertThrows(DataIntegrityException.class,
                () -> t.hydrateForLoad(TransferStatus.SENT, null, null, null));
        assertTrue(thrown.getMessage().contains("910"));
    }

    /**
     * A status nobody can read is refused rather than downgraded to the constructor's default.
     *
     * What it used to do was worse than losing the status. The parse and the hydrate call shared
     * one swallowing catch, so an unreadable value left the transfer CREATED - a SENT payment
     * reading as one that had not been authorized yet - and discarded the creation instant, the
     * authorization method, the decline reason and both OTP fields along with it.
     */
    @Test
    void aStoredRowWhoseStatusCannotBeReadIsRefusedRatherThanReadAsCreated() {
        JsonTransfer row = new JsonTransfer();
        row.id = 905;
        row.sourceAccountId = ACCOUNT_ID;
        row.targetIbanSnapshot = TARGET_IBAN;
        row.amount = new BigDecimal("1000.00");
        row.currency = "CZK";
        row.createdAt = "2026-01-01T09:00:00Z";
        row.status = "NOT_A_STATUS";

        DataIntegrityException thrown =
                assertThrows(DataIntegrityException.class, () -> JsonMapper.toDomain(row));
        assertTrue(thrown.getMessage().contains("NOT_A_STATUS"),
                "the refusal must name the value, so it can be corrected");
    }

    /**
     * Its creation instant, on the same terms, and this one was invisible rather than merely
     * wrong: {@code Transfer}'s constructor stamps {@code Instant.now()}, and the loader used to
     * overwrite it only when the stored value was non-null. So a row with no creation time came
     * back created at the moment it was read - a timestamp that changed on every reload and
     * sorted first in the ten-row panel that promises the newest.
     */
    @Test
    void aStoredRowWithNoCreationInstantIsRefusedRatherThanDatedOnLoad() {
        JsonTransfer row = new JsonTransfer();
        row.id = 906;
        row.sourceAccountId = ACCOUNT_ID;
        row.targetIbanSnapshot = TARGET_IBAN;
        row.amount = new BigDecimal("1000.00");
        row.currency = "CZK";
        row.status = "SENT";
        row.createdAt = null;

        DataIntegrityException thrown =
                assertThrows(DataIntegrityException.class, () -> JsonMapper.toDomain(row));
        assertTrue(thrown.getMessage().contains("906"),
                "the refusal must name the row, so a corrupt store can be found");
    }

    @Test
    void aStoredRowWhoseCreationInstantCannotBeReadIsRefused() {
        JsonTransfer row = new JsonTransfer();
        row.id = 907;
        row.sourceAccountId = ACCOUNT_ID;
        row.targetIbanSnapshot = TARGET_IBAN;
        row.amount = new BigDecimal("1000.00");
        row.currency = "CZK";
        row.status = "SENT";
        row.createdAt = "yesterday afternoon";

        DataIntegrityException thrown =
                assertThrows(DataIntegrityException.class, () -> JsonMapper.toDomain(row));
        assertTrue(thrown.getMessage().contains("yesterday afternoon"));
    }

    /**
     * A stored row with no currency at all is refused for that, rather than being assumed into
     * crowns. Same argument as the missing amount above: the store is a file people open, and a
     * key can be absent from it.
     */
    @Test
    void aStoredRowWithNoCurrencyIsRefusedRatherThanAssumedToBeCrowns() {
        JsonTransfer row = new JsonTransfer();
        row.id = 903;
        row.sourceAccountId = ACCOUNT_ID;
        row.targetIbanSnapshot = TARGET_IBAN;
        row.amount = new BigDecimal("1000.00");
        row.currency = null;
        row.status = "SENT";

        DataIntegrityException thrown =
                assertThrows(DataIntegrityException.class, () -> JsonMapper.toDomain(row));
        assertTrue(thrown.getMessage().contains("903"),
                "the refusal must name the row, so a corrupt store can be found");
    }
}
