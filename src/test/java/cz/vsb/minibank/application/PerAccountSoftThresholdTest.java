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
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * accounts.soft_daily_threshold_czk: the soft authorization tier is the account's own, and falls
 * back to the bank-wide default only when the account has no opinion.
 *
 * The defect this closes is not that a per-account value was missing in the abstract. It is that
 * on any account whose hard ceiling sits below the bank-wide 15 000 the soft tier could never
 * fire at all - every total that would have reached it had already been refused at the ceiling -
 * so the "two tiers" the rules describe were one tier on exactly the accounts most likely to
 * belong to a cautious customer. The demo's own secondary account, ceiling 8 000, was such an
 * account.
 *
 * Three accounts, one store, one clock:
 *   OVERRIDDEN  ceiling 8 000, own tier 3 000  - genuinely two-tiered
 *   BARE        ceiling 8 000, no override     - one tier, the ceiling, exactly as before
 *   DEFAULTED   ceiling 40 000, no override    - the bank-wide 15 000 still applies
 */
class PerAccountSoftThresholdTest {

    private static final int CUSTOMER_ID = 1;

    private static final int OVERRIDDEN_ACCOUNT_ID = 100;
    private static final int BARE_ACCOUNT_ID = 101;
    private static final int DEFAULTED_ACCOUNT_ID = 102;

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

        Customer customer = new Customer(CUSTOMER_ID, "Tier Probe", "tier@example.com",
                new Address("Hlavni 1", "Ostrava"));
        customer.addAccountId(OVERRIDDEN_ACCOUNT_ID);
        customer.addAccountId(BARE_ACCOUNT_ID);
        customer.addAccountId(DEFAULTED_ACCOUNT_ID);
        infra.customers.save(customer);

        infra.accounts.save(new Account(OVERRIDDEN_ACCOUNT_ID, new IBAN(OVERRIDDEN_IBAN),
                OPENING, SMALL_CEILING, OWN_SOFT_TIER));
        infra.accounts.save(new Account(BARE_ACCOUNT_ID, new IBAN(BARE_IBAN),
                OPENING, SMALL_CEILING));
        infra.accounts.save(new Account(DEFAULTED_ACCOUNT_ID, new IBAN(DEFAULTED_IBAN),
                OPENING, LARGE_CEILING));

        infra.customers.saveBeneficiary(CUSTOMER_ID, new Beneficiary(
                TRUSTED_BENEFICIARY_ID, "Trusted payee", new IBAN(TRUSTED_IBAN), true));

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
     * The account with an override really has two tiers: under 3 000 settles, over it asks for
     * authorization, and over the 8 000 ceiling is refused outright.
     *
     * Every amount here is far below the bank-wide 15 000, so against the old rule all three
     * payments below would have settled on the spot and only the ceiling would have spoken.
     */
    @Test
    void anAccountWithItsOwnTierAsksForAuthorizationLongBeforeItsCeiling() {
        assertEquals(TransferStatus.SENT, status(pay(OVERRIDDEN_ACCOUNT_ID, 2_000)),
                "2 000 is under this account's own 3 000 tier, so it settles");
        assertEquals(TransferStatus.SENT, status(pay(OVERRIDDEN_ACCOUNT_ID, 1_000)),
                "a day total of exactly 3 000 is not above the tier: the comparison is strict");

        int held = pay(OVERRIDDEN_ACCOUNT_ID, 0.01);
        assertEquals(TransferStatus.WAITING_AUTH, status(held),
                "one heller past this account's own tier must be held, though the bank-wide"
                        + " threshold is five times further away");

        service.authorizePayment(CUSTOMER_ID, held, FixedOtpValidator.DEMO_OTP);
        assertEquals(TransferStatus.SENT, status(held));

        // And the ceiling is still the ceiling: the tier does not replace it.
        int upToTheCeiling = pay(OVERRIDDEN_ACCOUNT_ID, 4_999.99);
        service.authorizePayment(CUSTOMER_ID, upToTheCeiling, FixedOtpValidator.DEMO_OTP);
        assertEquals(SMALL_CEILING, sentOnTheDay(OVERRIDDEN_ACCOUNT_ID));

        assertThrows(DailyLimitExceededException.class, () -> pay(OVERRIDDEN_ACCOUNT_ID, 0.01),
                "past the ceiling is still refused outright rather than turned into a prompt");
    }

    /**
     * The account with no override on the same 8 000 ceiling. This is the state item 6 exists
     * to correct, kept as a test so the difference is visible rather than asserted in prose:
     * with the bank-wide 15 000 applying, nothing on this account is ever held for the day
     * total, because 15 000 is unreachable behind an 8 000 ceiling.
     */
    @Test
    void anAccountWithNoOverrideAndALowCeilingHasOnlyOneTier() {
        assertNull(infra.accounts.byId(BARE_ACCOUNT_ID).orElseThrow().softDailyThreshold(),
                "no override: this account is on the bank-wide tier");

        assertEquals(TransferStatus.SENT, status(pay(BARE_ACCOUNT_ID, 4_000)));
        assertEquals(TransferStatus.SENT, status(pay(BARE_ACCOUNT_ID, 4_000)),
                "8 000 in a day settles without a prompt: the bank-wide 15 000 cannot be reached"
                        + " behind this account's own 8 000 ceiling, so the soft tier is inert");
        assertEquals(SMALL_CEILING, sentOnTheDay(BARE_ACCOUNT_ID));

        assertThrows(DailyLimitExceededException.class, () -> pay(BARE_ACCOUNT_ID, 0.01),
                "only the ceiling ever speaks on this account");
    }

    /**
     * And an account with no override and a ceiling above the default is untouched by this
     * change: the bank-wide 15 000 still decides, exactly as it did before there was a column.
     */
    @Test
    void anAccountWithNoOverrideStillFallsBackToTheBankWideThreshold() {
        assertEquals(TransferStatus.SENT, status(pay(DEFAULTED_ACCOUNT_ID, 15_000)),
                "a day total of exactly the bank-wide threshold still settles");
        assertEquals(TransferStatus.WAITING_AUTH, status(pay(DEFAULTED_ACCOUNT_ID, 0.01)),
                "and one heller past it is held, on the default rather than on any stored value");
    }

    // ------------------------------------------------------------------ fixture

    private int pay(int accountId, double amountCzk) {
        return service.submitPaymentByBeneficiary(
                CUSTOMER_ID, accountId, TRUSTED_BENEFICIARY_ID, amountCzk, "").transferId();
    }

    private TransferStatus status(int transferId) {
        return infra.transfers.byId(transferId).orElseThrow().status();
    }

    /** The store's own answer for the banking day the fixed clock sits in. */
    private Money sentOnTheDay(int accountId) {
        java.time.LocalDate day = java.time.LocalDate.ofInstant(
                NOON, TransferApplicationService.BANK_ZONE);
        return infra.transfers.sentTotalBetween(
                accountId,
                day.atStartOfDay(TransferApplicationService.BANK_ZONE).toInstant(),
                day.plusDays(1).atStartOfDay(TransferApplicationService.BANK_ZONE).toInstant());
    }
}
