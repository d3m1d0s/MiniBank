package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.customer.Account;
import cz.vsb.minibank.domain.customer.Address;
import cz.vsb.minibank.domain.customer.Beneficiary;
import cz.vsb.minibank.domain.customer.Customer;
import cz.vsb.minibank.domain.fraud.RuleBasedRiskService;
import cz.vsb.minibank.domain.transfer.TransferStatus;
import cz.vsb.minibank.domain.fee.ZeroFeePolicy;
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
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import cz.vsb.minibank.application.auth.FixedOtpValidator;
import cz.vsb.minibank.application.config.BootstrapServices;
import cz.vsb.minibank.application.payment.FakePaymentNetworkGateway;
import cz.vsb.minibank.application.payment.TransferApplicationService;

/**
 * customers.soft_daily_threshold_czk: the soft authorization tier is the customer's own, and falls
 * back to the bank-wide default only when the customer has no opinion.
 *
 * The defect this closes is not that a stored value was missing in the abstract. It is that a
 * customer whose hard ceiling sits below the bank-wide 15 000 could never reach the soft tier at
 * all - every total that would have reached it had already been refused at the ceiling - so the
 * "two tiers" the rules describe were one tier on exactly the customers most likely to be
 * cautious.
 *
 * This used to be PerAccountSoftThresholdTest, three accounts of one customer with a column each.
 * That arrangement was the thing the change removed: a ceiling per account is not a ceiling on
 * anybody, because the same person pays half out of each account. So the three cases are three
 * CUSTOMERS now, one account apiece:
 *   OVERRIDDEN  ceiling 8 000, own tier 3 000  - genuinely two-tiered
 *   BARE        ceiling 8 000, no override     - one tier, the ceiling, exactly as before
 *   DEFAULTED   ceiling 40 000, no override    - the bank-wide 15 000 still applies
 */
class CustomerSoftThresholdTest {

    private static final int OVERRIDDEN_CUSTOMER_ID = 1;
    private static final int BARE_CUSTOMER_ID = 2;
    private static final int DEFAULTED_CUSTOMER_ID = 3;

    private static final int OVERRIDDEN_ACCOUNT_ID = 100;
    private static final int BARE_ACCOUNT_ID = 101;
    private static final int DEFAULTED_ACCOUNT_ID = 102;

    /** One per customer: an address book belongs to a customer and not to the bank. */
    private static final int TRUSTED_BENEFICIARY_ID = 5001;

    private static final String OVERRIDDEN_IBAN = "CZ6508000000192000145399";
    private static final String BARE_IBAN = "CZ4308000000192000145407";
    private static final String DEFAULTED_IBAN = "CZ7408000000192000145431";
    private static final String TRUSTED_IBAN = "CZ2108000000192000145415";

    /** Large enough that the funds check never decides anything asserted here. */
    private static final Money OPENING = Money.czk(500_000);

    private static final Money SMALL_CEILING = Money.czk(8_000);
    private static final Money OWN_SOFT_TIER = Money.czk(3_000);
    private static final Money LARGE_CEILING = Money.czk(40_000);

    private static final Instant NOON = ZonedDateTime.of(
            2026, 7, 15, 12, 0, 0, 0, TransferApplicationService.BANK_ZONE).toInstant();

    @TempDir
    Path tempDir;

    private Bootstrap infra;
    private TransferApplicationService service;

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());

        openCustomer(OVERRIDDEN_CUSTOMER_ID, "Tier Probe", OVERRIDDEN_ACCOUNT_ID, OVERRIDDEN_IBAN,
                SMALL_CEILING, OWN_SOFT_TIER);
        openCustomer(BARE_CUSTOMER_ID, "Bare Probe", BARE_ACCOUNT_ID, BARE_IBAN,
                SMALL_CEILING, null);
        openCustomer(DEFAULTED_CUSTOMER_ID, "Default Probe", DEFAULTED_ACCOUNT_ID, DEFAULTED_IBAN,
                LARGE_CEILING, null);

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
     * The customer with an override really has two tiers: under 3 000 settles, over it asks for
     * authorization, and over the 8 000 ceiling is refused outright.
     *
     * Every amount here is far below the bank-wide 15 000, so against a bank-wide-only rule all
     * three payments below would have settled on the spot and only the ceiling would have spoken.
     */
    @Test
    void aCustomerWithTheirOwnTierIsAskedForAuthorizationLongBeforeTheirCeiling() {
        assertEquals(TransferStatus.SENT, status(pay(OVERRIDDEN_CUSTOMER_ID, OVERRIDDEN_ACCOUNT_ID, 2_000)),
                "2 000 is under this customer's own 3 000 tier, so it settles");
        assertEquals(TransferStatus.SENT, status(pay(OVERRIDDEN_CUSTOMER_ID, OVERRIDDEN_ACCOUNT_ID, 1_000)),
                "a day total of exactly 3 000 is not above the tier: the comparison is strict");

        int held = pay(OVERRIDDEN_CUSTOMER_ID, OVERRIDDEN_ACCOUNT_ID, 0.01);
        assertEquals(TransferStatus.WAITING_AUTH, status(held),
                "one heller past this customer's own tier must be held, though the bank-wide"
                        + " threshold is five times further away");

        service.authorizePayment(OVERRIDDEN_CUSTOMER_ID, held, FixedOtpValidator.DEMO_OTP);
        assertEquals(TransferStatus.SENT, status(held));

        // And the ceiling is still the ceiling: the tier does not replace it.
        int upToTheCeiling = pay(OVERRIDDEN_CUSTOMER_ID, OVERRIDDEN_ACCOUNT_ID, 4_999.99);
        service.authorizePayment(OVERRIDDEN_CUSTOMER_ID, upToTheCeiling, FixedOtpValidator.DEMO_OTP);
        assertEquals(SMALL_CEILING, sentOnTheDay(OVERRIDDEN_ACCOUNT_ID, OVERRIDDEN_IBAN));

        assertThrows(DailyLimitExceededException.class,
                () -> pay(OVERRIDDEN_CUSTOMER_ID, OVERRIDDEN_ACCOUNT_ID, 0.01),
                "past the ceiling is still refused outright rather than turned into a prompt");
    }

    /**
     * The customer with no override on the same 8 000 ceiling. Kept as a test so the difference is
     * visible rather than asserted in prose: with the bank-wide 15 000 applying, nothing here is
     * ever held for the day total, because 15 000 is unreachable behind an 8 000 ceiling.
     */
    @Test
    void aCustomerWithNoOverrideAndALowCeilingHasOnlyOneTier() {
        assertNull(infra.customers.byId(BARE_CUSTOMER_ID).orElseThrow().softDailyThreshold(),
                "no override: this customer is on the bank-wide tier");

        assertEquals(TransferStatus.SENT, status(pay(BARE_CUSTOMER_ID, BARE_ACCOUNT_ID, 4_000)));
        assertEquals(TransferStatus.SENT, status(pay(BARE_CUSTOMER_ID, BARE_ACCOUNT_ID, 4_000)),
                "8 000 in a day settles without a prompt: the bank-wide 15 000 cannot be reached"
                        + " behind this customer's own 8 000 ceiling, so the soft tier is inert");
        assertEquals(SMALL_CEILING, sentOnTheDay(BARE_ACCOUNT_ID, BARE_IBAN));

        assertThrows(DailyLimitExceededException.class,
                () -> pay(BARE_CUSTOMER_ID, BARE_ACCOUNT_ID, 0.01),
                "only the ceiling ever speaks for this customer");
    }

    /**
     * And a customer with no override and a ceiling above the default is untouched by any of this:
     * the bank-wide 15 000 still decides.
     */
    @Test
    void aCustomerWithNoOverrideStillFallsBackToTheBankWideThreshold() {
        assertEquals(TransferStatus.SENT,
                status(pay(DEFAULTED_CUSTOMER_ID, DEFAULTED_ACCOUNT_ID, 15_000)),
                "a day total of exactly the bank-wide threshold still settles");
        assertEquals(TransferStatus.WAITING_AUTH,
                status(pay(DEFAULTED_CUSTOMER_ID, DEFAULTED_ACCOUNT_ID, 0.01)),
                "and one heller past it is held, on the default rather than on any stored value");
    }

    // ------------------------------------------------------------------ fixture

    private void openCustomer(int customerId, String name, int accountId, String accountIban,
                              Money dailyLimit, Money softTier) {
        Customer customer = new Customer(customerId, name,
                name.toLowerCase().replace(' ', '.') + "@example.com",
                new Address("Hlavni 1", "Ostrava"), dailyLimit, softTier);
        customer.addAccountId(accountId);
        infra.customers.save(customer);
        infra.accounts.save(new Account(accountId, new IBAN(accountIban), OPENING));
        infra.customers.saveBeneficiary(customerId, new Beneficiary(
                TRUSTED_BENEFICIARY_ID, "Trusted payee", new IBAN(TRUSTED_IBAN), true));
    }

    private int pay(int customerId, int accountId, double amountCzk) {
        return service.submitPaymentByBeneficiary(
                customerId, accountId, TRUSTED_BENEFICIARY_ID, amountCzk, "").transferId();
    }

    private TransferStatus status(int transferId) {
        return infra.transfers.byId(transferId).orElseThrow().status();
    }

    /** The store's own answer for the banking day the fixed clock sits in. */
    private Money sentOnTheDay(int accountId, String accountIban) {
        java.time.LocalDate day = java.time.LocalDate.ofInstant(
                NOON, TransferApplicationService.BANK_ZONE);
        return infra.transfers.sentTotalLeavingCustomerBetween(
                List.of(accountId),
                List.of(accountIban),
                day.atStartOfDay(TransferApplicationService.BANK_ZONE).toInstant(),
                day.plusDays(1).atStartOfDay(TransferApplicationService.BANK_ZONE).toInstant());
    }
}
