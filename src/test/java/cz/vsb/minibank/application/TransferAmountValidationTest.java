package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.Transfer;
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
        int id = service.submitPaymentToIban(CUSTOMER_ID, ACCOUNT_ID, TARGET_IBAN, 0.01, "");

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
                () -> new Transfer(1, ACCOUNT_ID, null, TARGET_IBAN, Money.czk(-1), "CZK"));
        assertThrows(DataIntegrityException.class,
                () -> new Transfer(1, ACCOUNT_ID, null, TARGET_IBAN, Money.czk(0), "CZK"));
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
        row.amount = -1000.00;
        row.currency = "CZK";
        row.status = "SENT";

        assertThrows(DataIntegrityException.class, () -> JsonMapper.toDomain(row));
    }
}
