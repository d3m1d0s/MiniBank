package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FeePolicy;
import cz.vsb.minibank.domain.RuleBasedRiskService;
import cz.vsb.minibank.domain.SimpleFeePolicy;
import cz.vsb.minibank.domain.Transfer;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The daily limit is cumulative, has two tiers, and is enforced at both
 * creation and authorization.
 *
 * The ceiling and the tier are the CUSTOMER's, so a customer holding several accounts has one
 * day's allowance and not one per account. This class exercises a customer with a single account,
 * which is where the two arrangements agree; OwnAccountTransfersDoNotSpendTheDayTest takes the
 * case where they do not.
 *
 * What it replaced compared one amount against a per-account limit and, when that tripped, only
 * set requireAuthorization - which FixedOtpValidator satisfies with the compile-time constant
 * "0000". Measured against the store this project ships with: opening 25 000.00, 23 300.03 sent
 * in a day against a 15 000.00 limit, zero refusals. So every case here is written to fail
 * against that arrangement, not merely to describe this one.
 *
 * The clock is injected, never slept on. It has to reach two places to be worth anything: the
 * window the day total is asked for, and the createdAt every row is stamped with. Transfer
 * gained a constructor for the second, and without it a fixed clock would query one day and
 * write another, every total would come back zero, and these tests would pass while proving
 * that the feature does nothing.
 */
class DailyLimitTest {

    private static final int CUSTOMER_ID = 1;
    private static final int ACCOUNT_ID = 100;
    private static final int TRUSTED_BENEFICIARY_ID = 5001;

    private static final String ACCOUNT_IBAN = "CZ6508000000192000145399";
    /** Trusted, so the untrusted-and-high rule is out of the way and only the daily-limit rules can fire. */
    private static final String TRUSTED_IBAN = "CZ2108000000192000145415";
    /** Not a saved beneficiary and not an account of this bank, so payments to it are untrusted. */
    private static final String EXTERNAL_IBAN = "CZ2001000000000012345678";

    /** Large enough that the funds check never decides anything these tests assert. */
    private static final Money OPENING = Money.czk(500_000);

    /** The customer's hard ceiling, the stored customers.daily_limit_czk. */
    private static final Money CEILING = Money.czk(40_000);

    /** RuleBasedRiskService.AUTH_THRESHOLD_FOR_DAY_TOTAL, restated so the arithmetic reads. */
    private static final double SOFT_THRESHOLD = 15_000;

    /**
     * Summer time, so Prague is UTC+2 and a UTC day boundary is two hours away from the right
     * one. theBankingDayEndsAtPragueMidnightAndNotAtUtcMidnight depends on that gap.
     */
    private static final Instant DAY_ONE_NOON = pragueTime(2026, 7, 15, 12, 0, 0);
    private static final Instant DAY_ONE_LAST_MINUTE = pragueTime(2026, 7, 15, 23, 57, 0);
    private static final Instant DAY_ONE_LAST_SECOND = pragueTime(2026, 7, 15, 23, 59, 59);
    private static final Instant DAY_TWO_MIDNIGHT = pragueTime(2026, 7, 16, 0, 0, 0);
    private static final Instant DAY_TWO_JUST_AFTER = pragueTime(2026, 7, 16, 0, 1, 0);
    private static final Instant DAY_TWO_NOON = pragueTime(2026, 7, 16, 12, 0, 0);

    @TempDir
    Path tempDir;

    private Bootstrap infra;

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());

        Customer customer = new Customer(CUSTOMER_ID, "Limit Probe", "limit@example.com",
                new Address("Hlavni 1", "Ostrava"), CEILING);
        customer.addAccountId(ACCOUNT_ID);
        infra.customers.save(customer);
        infra.accounts.save(new Account(ACCOUNT_ID, new IBAN(ACCOUNT_IBAN), OPENING));
        infra.customers.saveBeneficiary(CUSTOMER_ID, new Beneficiary(
                TRUSTED_BENEFICIARY_ID, "Trusted payee", new IBAN(TRUSTED_IBAN), true));
    }

    // ------------------------------------------------------------------ the soft threshold

    /**
     * The tier the old rule was trying to be. Four payments that are individually unremarkable
     * - trusted beneficiary, each well under the 5 000 untrusted threshold's reach - and the
     * one that takes the day past 15 000 is held for authorization while the three before it
     * settled on the spot. Against the old per-amount comparison all four settle silently.
     */
    @Test
    void settledPaymentsAccumulateUntilTheSoftThresholdAsksForAuthorization() {
        var service = serviceAt(DAY_ONE_NOON);

        for (int i = 1; i <= 3; i++) {
            int id = payTrusted(service, 4_000);
            assertEquals(TransferStatus.SENT, status(id),
                    "payment " + i + " takes the day to " + (i * 4_000) + ", still under the threshold");
        }
        assertEquals(OPENING.minus(Money.czk(12_000)), balance());

        int held = payTrusted(service, 4_000);
        assertEquals(TransferStatus.WAITING_AUTH, status(held),
                "the payment that takes the day to 16 000 must be held, though nothing about it alone is risky");
        assertEquals(OPENING.minus(Money.czk(12_000)), balance(),
                "a held payment must not have debited anything");

        service.authorizePayment(CUSTOMER_ID, held, FixedOtpValidator.DEMO_OTP);
        assertEquals(TransferStatus.SENT, status(held));
        assertEquals(OPENING.minus(Money.czk(16_000)), balance());
    }

    /**
     * The boundary, both sides of it. The threshold is strictly-greater, like the untrusted one
     * next to it, so a day that lands exactly on 15 000.00 still settles and the tier begins one
     * heller later. Somebody will eventually be tempted to make one of these {@code gte}.
     */
    @Test
    void exactlyTheSoftThresholdStillSettlesAndOneHellerMoreDoesNot() {
        var service = serviceAt(DAY_ONE_NOON);

        assertEquals(TransferStatus.SENT, status(payTrusted(service, 5_000)));
        assertEquals(TransferStatus.SENT, status(payTrusted(service, 5_000)));
        assertEquals(TransferStatus.SENT, status(payTrusted(service, 5_000)),
                "a day total of exactly " + SOFT_THRESHOLD + " must still settle");

        assertEquals(TransferStatus.WAITING_AUTH, status(payTrusted(service, 0.01)),
                "one heller past the threshold must be held");
    }

    // ------------------------------------------------------------------ the hard ceiling

    /**
     * The other boundary and the refusal. A customer may spend exactly the ceiling; the payment
     * after that is refused outright rather than turned into an authorization prompt, and it
     * leaves no row and no movement behind.
     */
    @Test
    void exactlyTheCeilingIsAllowedAndTheNextPaymentIsRefused() {
        var service = serviceAt(DAY_ONE_NOON);

        for (int i = 0; i < 3; i++) {
            assertEquals(TransferStatus.SENT, status(payTrusted(service, 5_000)));
        }
        int upToTheCeiling = payTrusted(service, 25_000);
        assertEquals(TransferStatus.WAITING_AUTH, status(upToTheCeiling),
                "40 000 is past the soft threshold, so it is held rather than refused");
        service.authorizePayment(CUSTOMER_ID, upToTheCeiling, FixedOtpValidator.DEMO_OTP);
        assertEquals(TransferStatus.SENT, status(upToTheCeiling),
                "a day total of exactly the ceiling must be allowed");

        Money atTheCeiling = balance();
        assertEquals(OPENING.minus(CEILING), atTheCeiling);
        assertEquals(CEILING, sentOnDayOf(DAY_ONE_NOON));

        assertThrows(DailyLimitExceededException.class,
                () -> payTrusted(service, 0.01),
                "one heller past the ceiling must be refused, not held for an OTP");

        assertEquals(atTheCeiling, balance(), "a refused payment must move nothing");
        assertEquals(4, infra.transfers.bySourceAccount(ACCOUNT_ID).size(),
                "a refused payment must leave no row behind");
    }

    /**
     * Fees are charged on top of the amount and are deliberately outside the count, so a day
     * that ends exactly on the ceiling has taken slightly more than the ceiling out of the
     * account. Written as a test because it is the kind of decision a later reader will
     * otherwise "fix": with the fee counted, the second payment here would be refused.
     */
    @Test
    void feesAreChargedButAreNotCountedTowardTheCeiling() {
        var service = serviceAt(DAY_ONE_NOON, new SimpleFeePolicy());

        assertEquals(TransferStatus.SENT, status(payTrusted(service, 10_000)));

        int fillsTheCeiling = payTrusted(service, 30_000);
        assertEquals(TransferStatus.WAITING_AUTH, status(fillsTheCeiling));
        service.authorizePayment(CUSTOMER_ID, fillsTheCeiling, FixedOtpValidator.DEMO_OTP);
        assertEquals(TransferStatus.SENT, status(fillsTheCeiling),
                "10 000 + 30 000 is exactly the ceiling; counting the 100.00 fee would refuse it");

        // 1% up to 10 000, 1% plus 25.00 above it.
        Money fees = Money.czk(100).plus(Money.czk(325));
        assertEquals(OPENING.minus(CEILING).minus(fees), balance(),
                "the fees are still charged, they are only not counted");
        assertEquals(CEILING, sentOnDayOf(DAY_ONE_NOON), "the total is the amounts, not the debits");
    }

    /**
     * Only money that actually left counts. A cancelled payment never debited anything and a
     * pending one has not yet, so neither may consume the day's budget - and the pending one is
     * then refused at authorization, because by that time the budget really is spent.
     */
    @Test
    void cancelledAndPendingPaymentsDoNotConsumeTheDaysBudget() {
        var service = serviceAt(DAY_ONE_NOON);

        // Trusted, so the transfers land in WAITING_AUTH on the day-total rule rather than
        // being held for fraud review, which is what an untrusted 30 000 would now do. Both
        // amounts are over the 15 000 soft threshold, so the premise - a pending payment that
        // has debited nothing - is exactly the one this test was written for.
        int cancelled = payTrusted(service, 30_000);
        service.cancelPayment(CUSTOMER_ID, cancelled);
        assertEquals(TransferStatus.DECLINED, status(cancelled));

        int pending = payTrusted(service, 30_000);
        assertEquals(TransferStatus.WAITING_AUTH, status(pending));

        assertEquals(Money.czk(0), sentOnDayOf(DAY_ONE_NOON),
                "neither a declined nor a waiting payment has moved money");

        int fillsTheCeiling = payTrusted(service, 40_000);
        assertEquals(TransferStatus.WAITING_AUTH, status(fillsTheCeiling));
        service.authorizePayment(CUSTOMER_ID, fillsTheCeiling, FixedOtpValidator.DEMO_OTP);
        assertEquals(TransferStatus.SENT, status(fillsTheCeiling),
                "the whole ceiling is still available while nothing has settled");

        assertThrows(DailyLimitExceededException.class,
                () -> service.authorizePayment(CUSTOMER_ID, pending, FixedOtpValidator.DEMO_OTP),
                "the pending payment can no longer be authorized: the budget is spent now");
        assertEquals(TransferStatus.WAITING_AUTH, status(pending));
    }

    // ------------------------------------------------------------------ the authorization check

    /**
     * The case the check at authorization exists for, and the one a creation-only check cannot
     * see. Nothing is debited when a payment is held, so two payments of 25 000 each read a day
     * total of zero at creation and each pass against a 40 000 ceiling. They only collide when
     * the second is authorized.
     *
     * The refusal has to leave the transfer resolvable: still WAITING_AUTH, no OTP attempt
     * spent on a refusal that was not about the code, no decline reason written, and still
     * cancellable.
     */
    @Test
    void twoPendingPaymentsThatEachPassAtCreationCollideAtAuthorization() {
        var service = serviceAt(DAY_ONE_NOON);

        // Trusted for the same reason as above: 25 000 is over the alert threshold, and an
        // alerted payment would be held for review rather than waiting on the customer.
        int first = payTrusted(service, 25_000);
        int second = payTrusted(service, 25_000);
        assertEquals(TransferStatus.WAITING_AUTH, status(first));
        assertEquals(TransferStatus.WAITING_AUTH, status(second),
                "both pass at creation: neither has debited anything, so both see a day total of zero");

        service.authorizePayment(CUSTOMER_ID, first, FixedOtpValidator.DEMO_OTP);
        assertEquals(TransferStatus.SENT, status(first));

        assertThrows(DailyLimitExceededException.class,
                () -> service.authorizePayment(CUSTOMER_ID, second, FixedOtpValidator.DEMO_OTP),
                "50 000 against a 40 000 ceiling: the second authorization must be refused");

        Transfer left = infra.transfers.byId(second).orElseThrow();
        assertEquals(TransferStatus.WAITING_AUTH, left.status(), "the refusal must change nothing");
        assertEquals(0, left.authAttempts(),
                "a refusal that is not about the code must not spend an OTP attempt");
        assertNull(left.declineReason());
        assertEquals(OPENING.minus(Money.czk(25_000)), balance(),
                "only the payment that was authorized may have moved");

        service.cancelPayment(CUSTOMER_ID, second);
        assertEquals(TransferStatus.DECLINED, status(second), "the customer can still get rid of it");
    }

    /**
     * The drift settled_at closes, and the bypass that closing it must not open.
     *
     * Three untrusted 15 000 payments created at 23:57, held for fraud review, released by an
     * analyst, and authorized after midnight. Before settled_at a settled transfer carried only
     * its createdAt, so all three counted against day one however long the review took - the
     * per-day sum was right, but a single calendar day could see two days' budgets leave, and the review hold
     * removed the five-minute window that had bounded that gap. Now each counts against the day
     * it actually settled.
     *
     * The third is still refused, and for a better reason: two payments have settled on day two
     * for 30 000, and a third 15 000 would take day two to 45 000 against a 40 000 ceiling. So
     * the ceiling still binds across a midnight, which is the thing that must not regress.
     *
     * These three are untrusted 15 000 payments, so each one is over the fraud-alert threshold
     * and is held for review before it ever reaches the customer's confirmation step. Written
     * that way on purpose rather than routed round the alert with a trusted beneficiary: it is
     * the only place the re-check is exercised on a transfer an analyst released, which is now
     * a path a real payment takes.
     */
    @Test
    void aPaymentAuthorizedAfterMidnightIsCountedAgainstTheDayItSettlesOn() {
        var lateOnDayOne = servicesAt(DAY_ONE_LAST_MINUTE);

        int first = payExternal(lateOnDayOne.transferService, 15_000);
        int second = payExternal(lateOnDayOne.transferService, 15_000);
        int third = payExternal(lateOnDayOne.transferService, 15_000);
        assertEquals(TransferStatus.HELD_FOR_REVIEW, status(first));
        assertEquals(TransferStatus.HELD_FOR_REVIEW, status(second));
        assertEquals(TransferStatus.HELD_FOR_REVIEW, status(third));

        for (int id : new int[]{first, second, third}) {
            lateOnDayOne.fraudService.approve(id);
            assertEquals(TransferStatus.WAITING_AUTH, status(id),
                    "an analyst's approval releases the transfer, it does not send it");
        }
        assertEquals(Money.czk(0), sentOnDayOf(DAY_ONE_NOON),
                "releasing three transfers must have moved nothing");

        var afterMidnight = serviceAt(DAY_TWO_JUST_AFTER);

        afterMidnight.authorizePayment(CUSTOMER_ID, first, FixedOtpValidator.DEMO_OTP);
        afterMidnight.authorizePayment(CUSTOMER_ID, second, FixedOtpValidator.DEMO_OTP);
        assertEquals(TransferStatus.SENT, status(first));
        assertEquals(TransferStatus.SENT, status(second));

        assertThrows(DailyLimitExceededException.class,
                () -> afterMidnight.authorizePayment(CUSTOMER_ID, third, FixedOtpValidator.DEMO_OTP),
                "day two has already taken 30 000 of its 40 000, so a third 15 000 is refused");

        assertEquals(OPENING.minus(Money.czk(30_000)), balance());
        assertEquals(Money.czk(0), sentOnDayOf(DAY_ONE_NOON),
                "nothing settled on day one: all three payments were still held at midnight");
        assertEquals(Money.czk(30_000), sentOnDayOf(DAY_TWO_NOON),
                "they count against the day the money actually left, which is what settled_at records");

        Transfer settled = infra.transfers.byId(first).orElseThrow();
        assertEquals(DAY_TWO_JUST_AFTER, settled.settledAt(),
                "the stamp is the same instant the day window was taken from");
    }

    // ------------------------------------------------------------------ the day boundary

    /**
     * A new day starts the count over. Written with two fixed clocks over one store rather than
     * by waiting for a midnight.
     */
    @Test
    void yesterdaysSettledPaymentsDoNotCountTowardToday() {
        var dayOne = serviceAt(DAY_ONE_NOON);

        for (int i = 0; i < 3; i++) {
            payTrusted(dayOne, 5_000);
        }
        int big = payTrusted(dayOne, 25_000);
        dayOne.authorizePayment(CUSTOMER_ID, big, FixedOtpValidator.DEMO_OTP);
        assertEquals(CEILING, sentOnDayOf(DAY_ONE_NOON), "day one is full");
        assertThrows(DailyLimitExceededException.class, () -> payTrusted(dayOne, 0.01));

        var dayTwo = serviceAt(DAY_TWO_NOON);

        int fresh = payTrusted(dayTwo, 5_000);
        assertEquals(TransferStatus.SENT, status(fresh),
                "the next day's budget must be untouched by yesterday's payments");

        assertEquals(CEILING, sentOnDayOf(DAY_ONE_NOON));
        assertEquals(Money.czk(5_000), sentOnDayOf(DAY_TWO_NOON));
        assertEquals(OPENING.minus(CEILING).minus(Money.czk(5_000)), balance());
    }

    /**
     * Which midnight. In July, Prague is UTC+2: 23:59:59 Prague and 00:00:00 the next day are
     * 21:59:59Z and 22:00:00Z of the same UTC day, so a total bounded by a truncated UTC instant
     * would put both of these payments in one day and refuse the second. Deriving the bounds
     * from start-of-day in the zone is what makes them fall either side of the line, and the
     * third payment - one second later, same Prague day as the second - shows the line is
     * where it is claimed to be rather than merely somewhere.
     */
    @Test
    void theBankingDayEndsAtPragueMidnightAndNotAtUtcMidnight() {
        int lastSecondOfDayOne = settleTrusted(serviceAt(DAY_ONE_LAST_SECOND), 30_000);
        assertEquals(TransferStatus.SENT, status(lastSecondOfDayOne));

        int firstSecondOfDayTwo = settleTrusted(serviceAt(DAY_TWO_MIDNIGHT), 30_000);
        assertEquals(TransferStatus.SENT, status(firstSecondOfDayTwo),
                "midnight in Prague starts a new day, whatever the UTC date says");

        var laterOnDayTwo = serviceAt(DAY_TWO_JUST_AFTER);
        assertThrows(DailyLimitExceededException.class,
                () -> payExternal(laterOnDayTwo, 30_000),
                "and the same Prague day really does accumulate: 60 000 against a 40 000 ceiling");

        assertEquals(Money.czk(30_000), sentOnDayOf(DAY_ONE_NOON));
        assertEquals(Money.czk(30_000), sentOnDayOf(DAY_TWO_NOON));
    }

    // ------------------------------------------------------------------ nothing else changed

    /**
     * Guards against over-tightening, which is the way a limit usually breaks things. An
     * ordinary small payment still settles at creation and debits amount plus fee, and an
     * ordinary untrusted payment still waits for its OTP and then settles - both exactly as
     * they did before the daily limit, because neither goes anywhere near either tier.
     */
    @Test
    void theOrdinaryPathIsUnchanged() {
        var service = serviceAt(DAY_ONE_NOON, new SimpleFeePolicy());

        int settled = payTrusted(service, 2_000);
        assertEquals(TransferStatus.SENT, status(settled));
        assertEquals(OPENING.minus(Money.czk(2_000)).minus(Money.czk(20)), balance(),
                "the owner's payment still debits amount plus fee");

        int waiting = payExternal(service, 6_000);
        assertEquals(TransferStatus.WAITING_AUTH, status(waiting),
                "untrusted and over 5 000 still waits, on its own rule and not on a day total");

        service.authorizePayment(CUSTOMER_ID, waiting, FixedOtpValidator.DEMO_OTP);
        assertEquals(TransferStatus.SENT, status(waiting));
        assertEquals(OPENING.minus(Money.czk(2_000)).minus(Money.czk(20))
                        .minus(Money.czk(6_000)).minus(Money.czk(60)),
                balance());
    }

    // ------------------------------------------------------------------ fixture

    /** Zero fees, so the balance assertions are about the limit and not about the fee policy. */
    private TransferApplicationService serviceAt(Instant now) {
        return serviceAt(now, new ZeroFeePolicy());
    }

    private TransferApplicationService serviceAt(Instant now, FeePolicy policy) {
        return servicesAt(now, policy).transferService;
    }

    /** The whole bundle, for the one test that needs the fraud desk to release a held payment. */
    private BootstrapServices servicesAt(Instant now) {
        return servicesAt(now, new ZeroFeePolicy());
    }

    /**
     * Services pinned to one instant. The repositories are the same objects every time, so
     * successive clocks read and write one store - which is what lets one test place payments
     * on two different days.
     */
    private BootstrapServices servicesAt(Instant now, FeePolicy policy) {
        return new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                policy,
                new RuleBasedRiskService(),
                new FixedOtpValidator(),
                new FakePaymentNetworkGateway(),
                infra.uowFactory,
                Clock.fixed(now, TransferApplicationService.BANK_ZONE)
        );
    }

    private int payTrusted(TransferApplicationService service, double amountCzk) {
        return service.submitPaymentByBeneficiary(
                CUSTOMER_ID, ACCOUNT_ID, TRUSTED_BENEFICIARY_ID, amountCzk, "").transferId();
    }

    private int payExternal(TransferApplicationService service, double amountCzk) {
        return service.submitPaymentToIban(CUSTOMER_ID, ACCOUNT_ID, EXTERNAL_IBAN, amountCzk, "").transferId();
    }

    /**
     * A payment over the soft threshold always waits, so settling one takes two calls. Trusted,
     * so the amounts here are held on the day-total rule and not for fraud review - a held
     * transfer would refuse the code rather than settle.
     */
    private int settleTrusted(TransferApplicationService service, double amountCzk) {
        int id = payTrusted(service, amountCzk);
        service.authorizePayment(CUSTOMER_ID, id, FixedOtpValidator.DEMO_OTP);
        return id;
    }

    private TransferStatus status(int transferId) {
        return infra.transfers.byId(transferId).orElseThrow().status();
    }

    private Money balance() {
        return infra.accounts.byId(ACCOUNT_ID).orElseThrow().balance();
    }

    /**
     * The store's own answer for the banking day containing the given instant, asked the way the
     * ceiling asks it: over the customer's accounts, dropping anything that only moved to another
     * of them. This customer holds one account, so the second collection excludes nothing here -
     * OwnAccountTransfersDoNotSpendTheDayTest is where it has something to exclude.
     */
    private Money sentOnDayOf(Instant when) {
        LocalDate day = LocalDate.ofInstant(when, TransferApplicationService.BANK_ZONE);
        return infra.transfers.sentTotalLeavingCustomerBetween(
                List.of(ACCOUNT_ID),
                List.of(ACCOUNT_IBAN),
                day.atStartOfDay(TransferApplicationService.BANK_ZONE).toInstant(),
                day.plusDays(1).atStartOfDay(TransferApplicationService.BANK_ZONE).toInstant());
    }

    private static Instant pragueTime(int year, int month, int day, int hour, int minute, int second) {
        return ZonedDateTime.of(year, month, day, hour, minute, second, 0,
                TransferApplicationService.BANK_ZONE).toInstant();
    }
}
