package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.RuleBasedRiskService;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.ZeroFeePolicy;
import cz.vsb.minibank.domain.exceptions.DailyLimitExceededException;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The daily ceiling bounds a PERSON, and moving money between two accounts of that person does
 * not spend any of it.
 *
 * Two rules meet here and neither is obvious on its own.
 *
 * The first is that the day's total spans every account the customer holds. Before it did, a
 * customer with two accounts had two independent allowances and could spend the sum of them by
 * paying half out of each - and this application exists to notice exactly that kind of splitting.
 * theTwoAccountsShareOneAllowance is that half.
 *
 * The second is that totalling both accounts is not by itself the right total. A payment from one
 * of them to the other has left neither the customer nor the bank's books, and counting it would
 * hand the customer a way to exhaust - or, read the other way, to inflate - their own day by
 * shuffling money in place. The predicate is narrower than "stayed in this bank": a trusted payee
 * may keep an account here too, and paying them has spent the money as surely as paying somebody
 * across the network. aTrustedPayeeWhoBanksHereStillCounts is the case that separates the two, and
 * it is the case a "did this stay inside the bank" test would pass while being wrong.
 *
 * Everything below settles at creation. The customer's own soft tier is set above their ceiling on
 * purpose, so no payment is ever held for authorization and no assertion here is about which tier
 * spoke: what is being measured is the total, and a held payment has moved nothing to measure.
 * The beneficiaries are all trusted for the same reason - an untrusted 30 000 would be held for
 * fraud review before any of this got a chance to happen.
 */
class OwnAccountTransfersDoNotSpendTheDayTest {

    private static final int CUSTOMER_ID = 1;
    private static final int FIRST_ACCOUNT_ID = 100;
    private static final int SECOND_ACCOUNT_ID = 101;

    /** Somebody else, holding an account at this same bank. */
    private static final int NEIGHBOUR_CUSTOMER_ID = 2;
    private static final int NEIGHBOUR_ACCOUNT_ID = 200;

    private static final int OWN_SECOND_BENEFICIARY_ID = 5001;
    private static final int NEIGHBOUR_BENEFICIARY_ID = 5002;
    private static final int EXTERNAL_BENEFICIARY_ID = 5003;

    private static final String FIRST_IBAN = "CZ6508000000192000145399";
    private static final String SECOND_IBAN = "CZ4308000000192000145407";
    private static final String NEIGHBOUR_IBAN = "CZ7408000000192000145431";

    /** No account of this bank, so a payment to it really does leave. */
    private static final String EXTERNAL_IBAN = "CZ2001000000000012345678";

    /** Large enough on both accounts that the funds check never decides anything asserted here. */
    private static final Money OPENING = Money.czk(500_000);

    private static final Money CEILING = Money.czk(40_000);

    /** Above the ceiling, so the soft tier is inert and only the ceiling ever speaks. */
    private static final Money INERT_SOFT_TIER = Money.czk(1_000_000);

    private static final Instant NOON = ZonedDateTime.of(
            2026, 7, 15, 12, 0, 0, 0, TransferApplicationService.BANK_ZONE).toInstant();

    @TempDir
    Path tempDir;

    private Bootstrap infra;
    private TransferApplicationService service;

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());

        Customer customer = new Customer(CUSTOMER_ID, "Two Account Probe", "two@example.com",
                new Address("Hlavni 1", "Ostrava"), CEILING, INERT_SOFT_TIER);
        customer.addAccountId(FIRST_ACCOUNT_ID);
        customer.addAccountId(SECOND_ACCOUNT_ID);
        infra.customers.save(customer);
        infra.accounts.save(new Account(FIRST_ACCOUNT_ID, new IBAN(FIRST_IBAN), OPENING));
        infra.accounts.save(new Account(SECOND_ACCOUNT_ID, new IBAN(SECOND_IBAN), OPENING));

        Customer neighbour = new Customer(NEIGHBOUR_CUSTOMER_ID, "Neighbour",
                "neighbour@example.com", new Address("Hlavni 2", "Ostrava"), CEILING);
        neighbour.addAccountId(NEIGHBOUR_ACCOUNT_ID);
        infra.customers.save(neighbour);
        infra.accounts.save(new Account(NEIGHBOUR_ACCOUNT_ID, new IBAN(NEIGHBOUR_IBAN),
                Money.czk(1_000)));

        infra.customers.saveBeneficiary(CUSTOMER_ID, new Beneficiary(
                OWN_SECOND_BENEFICIARY_ID, "My other account", new IBAN(SECOND_IBAN), true));
        infra.customers.saveBeneficiary(CUSTOMER_ID, new Beneficiary(
                NEIGHBOUR_BENEFICIARY_ID, "Neighbour", new IBAN(NEIGHBOUR_IBAN), true));
        infra.customers.saveBeneficiary(CUSTOMER_ID, new Beneficiary(
                EXTERNAL_BENEFICIARY_ID, "Somebody elsewhere", new IBAN(EXTERNAL_IBAN), true));

        service = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts,
                new ZeroFeePolicy(),
                new RuleBasedRiskService(),
                new FixedOtpValidator(),
                new FakePaymentNetworkGateway(),
                infra.uowFactory,
                Clock.fixed(NOON, TransferApplicationService.BANK_ZONE)
        ).transferService;
    }

    /**
     * The heart of it. 30 000 moved to the customer's own other account settles, the money is
     * really there afterwards, and the day's total is still zero - so the whole 40 000 is still
     * available and a payment of exactly that much settles behind it.
     *
     * Against a total that merely summed the two accounts, the second payment here is refused:
     * 30 000 plus 40 000 against a 40 000 ceiling.
     */
    @Test
    void aPaymentToTheCustomersOwnOtherAccountDoesNotSpendTheDay() {
        int internal = pay(FIRST_ACCOUNT_ID, OWN_SECOND_BENEFICIARY_ID, 30_000);
        assertEquals(TransferStatus.SENT, status(internal), "an internal move still settles");
        assertEquals(OPENING.plus(Money.czk(30_000)), balance(SECOND_ACCOUNT_ID),
                "and the money really arrived: this is a move, not a disappearance");

        assertEquals(Money.czk(0), sentOutToday(),
                "nothing has left this customer, so nothing has been spent from their day");

        int outward = pay(FIRST_ACCOUNT_ID, EXTERNAL_BENEFICIARY_ID, 40_000);
        assertEquals(TransferStatus.SENT, status(outward),
                "the whole ceiling is still available: the internal move consumed none of it");
        assertEquals(CEILING, sentOutToday());
    }

    /**
     * The other side of the same predicate. A trusted payee who happens to bank here has been
     * paid, so their 30 000 counts, and only 10 000 of the day is left afterwards.
     *
     * This is what a rule keyed on "did the money stay inside this bank" would get wrong: it
     * would drop this payment too, and a customer could spend without limit as long as they paid
     * people who bank at the same place.
     */
    @Test
    void aTrustedPayeeWhoBanksHereStillCounts() {
        int inBank = pay(FIRST_ACCOUNT_ID, NEIGHBOUR_BENEFICIARY_ID, 30_000);
        assertEquals(TransferStatus.SENT, status(inBank));
        assertEquals(Money.czk(1_000).plus(Money.czk(30_000)), balance(NEIGHBOUR_ACCOUNT_ID),
                "the payee is credited here rather than over the network, which is the point");

        assertEquals(Money.czk(30_000), sentOutToday(),
                "the money left this customer, whoever ended up holding it");

        assertEquals(TransferStatus.SENT,
                status(pay(FIRST_ACCOUNT_ID, EXTERNAL_BENEFICIARY_ID, 10_000)),
                "10 000 is what the day has left, and it settles");
        assertEquals(CEILING, sentOutToday());

        assertThrows(DailyLimitExceededException.class,
                () -> pay(FIRST_ACCOUNT_ID, EXTERNAL_BENEFICIARY_ID, 0.01),
                "one heller past the ceiling is refused, in-bank payee or not");
    }

    /**
     * One allowance for the person, not one per account. Half out of each account is the whole
     * of the defect the customer-wide total closes, and it is the arrangement the demo dataset
     * itself had: two accounts, two ceilings, 48 000 spendable behind a 40 000 rule.
     */
    @Test
    void theTwoAccountsShareOneAllowance() {
        assertEquals(TransferStatus.SENT,
                status(pay(FIRST_ACCOUNT_ID, EXTERNAL_BENEFICIARY_ID, 25_000)));

        assertThrows(DailyLimitExceededException.class,
                () -> pay(SECOND_ACCOUNT_ID, EXTERNAL_BENEFICIARY_ID, 25_000),
                "50 000 against a 40 000 ceiling, paid out of two accounts of one customer");

        assertEquals(Money.czk(25_000), sentOutToday(), "a refused payment moves nothing");
        assertEquals(OPENING, balance(SECOND_ACCOUNT_ID));
    }

    /**
     * The one place the rule is stricter than the total it extends, stated rather than
     * discovered: the amount being attempted is added to the day whatever its destination, so an
     * internal move counts while it is being made and stops counting once it has settled.
     *
     * Strict in the safe direction, and the alternative - excusing the attempt because its
     * destination is the customer's own - would be a payment that never had to be declared to
     * the ceiling at all.
     */
    @Test
    void anInternalMoveIsStillMeasuredWhileItIsBeingMade() {
        assertEquals(TransferStatus.SENT,
                status(pay(FIRST_ACCOUNT_ID, EXTERNAL_BENEFICIARY_ID, 40_000)));
        assertEquals(CEILING, sentOutToday(), "the day is spent");

        assertThrows(DailyLimitExceededException.class,
                () -> pay(FIRST_ACCOUNT_ID, OWN_SECOND_BENEFICIARY_ID, 0.01),
                "the attempt is measured against the ceiling before its destination is forgiven");

        assertEquals(OPENING, balance(SECOND_ACCOUNT_ID), "and it moved nothing");
    }

    /**
     * The exclusion at the store, underneath the service.
     *
     * The plain per-account total still counts the internal row - it excludes nothing, which is
     * what makes it the form the per-backend row-level tests state their rules in - while the
     * customer-wide total, given the customer's own IBANs, drops it. Asserting both against one
     * stored row is what shows the difference is the destination predicate and not the window,
     * the status or the currency.
     */
    @Test
    void theStoreIsWhereTheDestinationIsDropped() {
        pay(FIRST_ACCOUNT_ID, OWN_SECOND_BENEFICIARY_ID, 30_000);

        LocalDate day = LocalDate.ofInstant(NOON, TransferApplicationService.BANK_ZONE);
        Instant from = day.atStartOfDay(TransferApplicationService.BANK_ZONE).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(TransferApplicationService.BANK_ZONE).toInstant();

        assertEquals(Money.czk(30_000),
                infra.transfers.sentTotalBetween(FIRST_ACCOUNT_ID, from, to),
                "the single-account total excludes nothing and counts the row");

        assertEquals(Money.czk(0),
                infra.transfers.sentTotalLeavingCustomerBetween(
                        List.of(FIRST_ACCOUNT_ID, SECOND_ACCOUNT_ID),
                        List.of(FIRST_IBAN, SECOND_IBAN),
                        from, to),
                "and the customer-wide total drops it, because it landed on their own account");
    }

    // ------------------------------------------------------------------ fixture

    private int pay(int sourceAccountId, int beneficiaryId, double amountCzk) {
        return service.submitPaymentByBeneficiary(
                CUSTOMER_ID, sourceAccountId, beneficiaryId, amountCzk, "").transferId();
    }

    private TransferStatus status(int transferId) {
        return infra.transfers.byId(transferId).orElseThrow().status();
    }

    private Money balance(int accountId) {
        return infra.accounts.byId(accountId).orElseThrow().balance();
    }

    /**
     * The service's own reading of the day, which is also what the accounts endpoint answers
     * with. Asked through the public read rather than off the repository, so that what these
     * tests measure and what a customer is shown are one number.
     */
    private Money sentOutToday() {
        return service.sentOutTodayBy(CUSTOMER_ID);
    }
}
