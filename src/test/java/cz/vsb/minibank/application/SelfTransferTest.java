package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.exceptions.SelfTransferNotAllowedException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A payment to the source account's own IBAN debits the account and credits nobody, because
 * there is no credit leg. Until there is one, such a payment destroys money rather than
 * moving it, so it is refused outright.
 */
class SelfTransferTest {

    private static final int CUSTOMER_ID = 1;
    private static final int ACCOUNT_ID = 100;
    private static final int SECOND_ACCOUNT_ID = 101;
    private static final int SELF_BENEFICIARY_ID = 5001;

    private static final String OWN_IBAN = "CZ6508000000192000145399";
    private static final String SECOND_IBAN = "CZ4308000000192000145407";
    private static final String OUTSIDE_IBAN = "CZ2001000000000012345678";

    private static final Money OPENING = Money.czk(25_000);

    @TempDir
    Path tempDir;

    private TransferApplicationService service;
    private AccountRepository accounts;
    private TransferRepository transfers;

    @BeforeEach
    void setUp() {
        Bootstrap infra = new Bootstrap(tempDir.resolve("data.json").toString());
        accounts = infra.accounts;
        transfers = infra.transfers;

        CustomerRepository customers = infra.customers;
        Customer customer = new Customer(CUSTOMER_ID, "Self Test", "self@example.com",
                new Address("Test Street 1", "Ostrava"));
        customer.addAccountId(ACCOUNT_ID);
        customer.addAccountId(SECOND_ACCOUNT_ID);
        customers.save(customer);

        accounts.save(new Account(ACCOUNT_ID, new IBAN(OWN_IBAN), OPENING, Money.czk(100_000)));
        accounts.save(new Account(SECOND_ACCOUNT_ID, new IBAN(SECOND_IBAN),
                Money.czk(5_000), Money.czk(100_000)));

        // A beneficiary that points back at the customer's own source account, which is how
        // the same mistake arrives through the saved-payee path.
        customers.saveBeneficiary(CUSTOMER_ID,
                new Beneficiary(SELF_BENEFICIARY_ID, "Myself", new IBAN(OWN_IBAN), true));

        service = new BootstrapServices(customers, accounts, transfers,
                infra.alerts, infra.uowFactory).transferService;
    }

    private void assertNothingMoved() {
        assertEquals(OPENING, accounts.byId(ACCOUNT_ID).orElseThrow().balance());
        assertTrue(transfers.bySourceAccount(ACCOUNT_ID).isEmpty());
    }

    @Test
    void payingTheSourceAccountsOwnIbanIsRefused() {
        assertThrows(SelfTransferNotAllowedException.class,
                () -> service.submitPaymentToIban(CUSTOMER_ID, ACCOUNT_ID, OWN_IBAN, 500, ""));

        assertNothingMoved();
    }

    /**
     * The IBAN is normalized before it is compared, so a formatted copy of the same account
     * is the same account.
     */
    @Test
    void aFormattedCopyOfTheOwnIbanIsAlsoRefused() {
        assertThrows(SelfTransferNotAllowedException.class,
                () -> service.submitPaymentToIban(
                        CUSTOMER_ID, ACCOUNT_ID, "cz65 0800 0000 1920 0014 5399", 500, ""));

        assertNothingMoved();
    }

    @Test
    void aSavedBeneficiaryPointingAtTheSourceAccountIsRefused() {
        assertThrows(SelfTransferNotAllowedException.class,
                () -> service.submitPaymentByBeneficiary(
                        CUSTOMER_ID, ACCOUNT_ID, SELF_BENEFICIARY_ID, 500, ""));

        assertNothingMoved();
    }

    /**
     * Guards against over-reaching: only the source account itself is refused. Paying the
     * customer's other account is an ordinary transfer as far as this rule is concerned.
     */
    @Test
    void payingTheCustomersOtherAccountIsStillAllowed() {
        int id = service.submitPaymentToIban(CUSTOMER_ID, ACCOUNT_ID, SECOND_IBAN, 500, "");

        assertEquals(1, transfers.bySourceAccount(ACCOUNT_ID).size());
        assertEquals(id, transfers.bySourceAccount(ACCOUNT_ID).get(0).id());
    }

    @Test
    void payingAnOutsideIbanIsStillAllowed() {
        service.submitPaymentToIban(CUSTOMER_ID, ACCOUNT_ID, OUTSIDE_IBAN, 500, "");

        assertEquals(1, transfers.bySourceAccount(ACCOUNT_ID).size());
        assertEquals(OPENING.minus(Money.czk(500)),
                accounts.byId(ACCOUNT_ID).orElseThrow().balance());
    }
}
