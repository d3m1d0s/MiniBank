package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.account.MyAccountsResponseDto;
import cz.vsb.minibank.api.dto.payment.NewPaymentRequest;
import cz.vsb.minibank.application.config.BootstrapServices;
import cz.vsb.minibank.application.auth.SecurityContext;
import cz.vsb.minibank.domain.customer.Account;
import cz.vsb.minibank.domain.customer.Address;
import cz.vsb.minibank.domain.customer.Beneficiary;
import cz.vsb.minibank.domain.customer.Customer;
import cz.vsb.minibank.domain.customer.User;
import cz.vsb.minibank.domain.customer.UserRole;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import cz.vsb.minibank.api.controller.PaymentController;
import cz.vsb.minibank.api.dto.account.DailyOutflowDto;

/**
 * GET /api/me/accounts answers one day for a customer holding two accounts, and it is the same
 * number the refusal is measured against.
 *
 * The shape is the whole point of the change, so it is worth stating on the wire and not only in
 * the service. A customer with two accounts used to be told two allowances and two running totals,
 * one hanging off each account, and the sum of those allowances was what they could actually spend
 * by paying half out of each. The response now carries one {@code today} beside the list, and this
 * class pins the two things that make it true rather than merely tidy:
 *
 *   - a payment out of either account moves the one total, and
 *   - a payment from one of the customer's accounts to the other moves it not at all, because that
 *     money has left neither the customer nor this bank's books.
 *
 * The second is what a screen built on this response would otherwise get wrong in the direction
 * that costs the customer money: shown a day inflated by their own transfers between their own
 * accounts, they would be refused a payment the bank would in fact have settled.
 *
 * Everything here is paid to a TRUSTED payee, and the amounts stay under the bank-wide 15 000 soft
 * tier, so every payment settles at once. A payment held for a code has moved nothing and would
 * make the totals below assert the holding rather than the day.
 */
class MyAccountsDayIsOneCustomersTest {

    private static final int CUSTOMER_ID = 7;
    private static final int FIRST_ACCOUNT_ID = 701;
    private static final int SECOND_ACCOUNT_ID = 702;

    private static final int OWN_SECOND_BENEFICIARY_ID = 7101;
    private static final int EXTERNAL_BENEFICIARY_ID = 7102;

    private static final String FIRST_IBAN = "CZ6508000000192000145399";
    private static final String SECOND_IBAN = "CZ4308000000192000145407";

    /** No account of this bank, so a payment to it really does leave the customer. */
    private static final String EXTERNAL_IBAN = "CZ2001000000000012345678";

    private static final Money CEILING = Money.czk(40_000);

    /** Large enough on both accounts that the funds check never decides anything asserted here. */
    private static final Money OPENING = Money.czk(200_000);

    @TempDir
    Path tempDir;

    private PaymentController paymentController;

    @BeforeEach
    void setUp() {
        Bootstrap infra = new Bootstrap(tempDir.resolve("data.json").toString());

        Customer customer = new Customer(CUSTOMER_ID, "Two Account Customer",
                "two.accounts@example.com", new Address("Hlavni 1", "Ostrava"), CEILING);
        customer.addAccountId(FIRST_ACCOUNT_ID);
        customer.addAccountId(SECOND_ACCOUNT_ID);
        infra.customers.save(customer);

        infra.accounts.save(new Account(FIRST_ACCOUNT_ID, new IBAN(FIRST_IBAN), OPENING));
        infra.accounts.save(new Account(SECOND_ACCOUNT_ID, new IBAN(SECOND_IBAN), OPENING));

        // Trusted, so nothing below is held for review or for a code on the payee's account.
        infra.customers.saveBeneficiary(CUSTOMER_ID, new Beneficiary(
                OWN_SECOND_BENEFICIARY_ID, "My other account", new IBAN(SECOND_IBAN), true));
        infra.customers.saveBeneficiary(CUSTOMER_ID, new Beneficiary(
                EXTERNAL_BENEFICIARY_ID, "Somebody elsewhere", new IBAN(EXTERNAL_IBAN), true));

        SecurityContext.setCurrentUser(new User(1, "two-accounts", new byte[0], new byte[0],
                UserRole.CUSTOMER, CUSTOMER_ID));

        BootstrapServices services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts, infra.uowFactory);

        paymentController = new PaymentController(services.transferService, infra.accounts,
                services.ownershipGuard, services.feePolicy, infra.uowFactory);
    }

    @Test
    void twoAccountsComeBackWithOneDayAndOneCeilingBetweenThem() {
        MyAccountsResponseDto response = paymentController.listMyAccounts();

        assertEquals(2, response.accounts().size(), "both accounts of this customer are listed");
        assertNotNull(response.today(), "and the day they share travels with them");

        assertEquals("0.00", response.today().sentOut().amount(),
                "nothing has settled out of this customer yet today");
        assertEquals("40000.00", response.today().limit().amount(),
                "one ceiling for the person, not one per account");
    }

    /**
     * A payment out of either account spends the same day.
     *
     * Paid out of the second account rather than the first, because a total that had quietly
     * stayed per-account would answer zero here while the money was gone.
     */
    @Test
    void aPaymentOutOfEitherAccountMovesTheOneTotal() {
        pay(FIRST_ACCOUNT_ID, EXTERNAL_BENEFICIARY_ID, 3_000.0);
        assertEquals("3000.00", today().sentOut().amount());

        pay(SECOND_ACCOUNT_ID, EXTERNAL_BENEFICIARY_ID, 4_000.0);
        assertEquals("7000.00", today().sentOut().amount(),
                "the day is the customer's, so the second account's payment adds to the first's");
    }

    /**
     * And money moved between the customer's own two accounts adds nothing to it.
     *
     * The balances are checked as well as the total, so this cannot pass by the payment having
     * failed: the money really did move, and it still did not count as having gone.
     */
    @Test
    void movingMoneyBetweenTheirOwnAccountsAddsNothingToTheDay() {
        pay(FIRST_ACCOUNT_ID, OWN_SECOND_BENEFICIARY_ID, 12_000.0);

        assertEquals("0.00", today().sentOut().amount(),
                "nothing has left this customer, so nothing has been spent from their day");

        MyAccountsResponseDto response = paymentController.listMyAccounts();
        assertEquals(OPENING.plus(Money.czk(12_000)).amount().toPlainString(),
                balanceOf(response, SECOND_ACCOUNT_ID),
                "and the money really arrived: this is a move, not a disappearance");

        // The consequence a customer meets. 15 000 is exactly the bank-wide soft tier, so it
        // settles at once on a day that is still empty. Against a total that had counted the
        // internal move, the day would already stand at 12 000, this payment would cross the tier,
        // and the customer would be asked for a code they should never have been asked for.
        pay(FIRST_ACCOUNT_ID, EXTERNAL_BENEFICIARY_ID, 15_000.0);
        assertEquals("15000.00", today().sentOut().amount(),
                "the internal move consumed none of the allowance, so this settled on the spot");
    }

    // ------------------------------------------------------------------ fixture

    private void pay(int sourceAccountId, int beneficiaryId, double amountCzk) {
        paymentController.createPayment(new NewPaymentRequest(
                sourceAccountId, null, beneficiaryId, amountCzk, "counts or does not"));
    }

    private cz.vsb.minibank.api.dto.account.DailyOutflowDto today() {
        return paymentController.listMyAccounts().today();
    }

    private String balanceOf(MyAccountsResponseDto response, int accountId) {
        return response.accounts().stream()
                .filter(a -> a.id() == accountId)
                .findFirst()
                .orElseThrow()
                .balance()
                .amount();
    }
}
