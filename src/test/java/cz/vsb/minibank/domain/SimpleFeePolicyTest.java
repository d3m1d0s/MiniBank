package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.value.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the published fee schedule, and in particular its two boundaries.
 *
 * The schedule was previously asserted nowhere. Four literal fee values existed in the whole
 * suite, all of them in {@code DailyLimitTest} and all in the one percent tier, so the free
 * boundary could be moved without a single test going red. Everything else that mentions
 * {@code SimpleFeePolicy} compares {@code compute()} against {@code compute()} and passes under
 * any formula whatsoever.
 *
 * Both boundaries are inclusive on the cheaper side, and the fee is charged on the whole amount
 * rather than on the slice above each boundary. Both are deliberate, both are invisible from
 * anywhere else in the tree, and both are what this class exists to state.
 */
class SimpleFeePolicyTest {

    private final SimpleFeePolicy policy = new SimpleFeePolicy();

    // -------------------------------------------------------------------------
    // The free tier, up to and including 1 000.00
    // -------------------------------------------------------------------------

    @Test
    void nothingIsChargedUpToTheFreeBoundary() {
        assertEquals(Money.czk(0.00), policy.compute(Money.czk(0.01)));
        assertEquals(Money.czk(0.00), policy.compute(Money.czk(500)));
        assertEquals(Money.czk(0.00), policy.compute(Money.czk(999.99)));
    }

    @Test
    void theFreeBoundaryIsItselfFree() {
        assertEquals(Money.czk(0.00), policy.compute(Money.czk(1_000.00)),
                "1 000.00 belongs to the free tier, not to the one percent tier");
    }

    // -------------------------------------------------------------------------
    // The one percent tier, up to and including 10 000.00
    // -------------------------------------------------------------------------

    @Test
    void oneHellerPastTheFreeBoundaryIsChargedOnTheWholeAmount() {
        assertEquals(Money.czk(10.00), policy.compute(Money.czk(1_000.01)),
                "the percentage is taken on the whole amount, not on the 0.01 above the boundary");
    }

    @Test
    void theOnePercentTierIsOnePercentOfTheWholeAmount() {
        assertEquals(Money.czk(20.00), policy.compute(Money.czk(2_000)));
        assertEquals(Money.czk(60.00), policy.compute(Money.czk(6_000)));
    }

    @Test
    void theLastHellerBelowTenThousandAlreadyPaysWhatTenThousandPays() {
        // 9 999.99 is one percent of 99.9999, which rounds half up to the same 100.00 that
        // 10 000.00 pays. The top of the tier is flat, so the boundary is where the fee moves
        // and 0.01 below it is not.
        assertEquals(Money.czk(100.00), policy.compute(Money.czk(9_999.99)));
    }

    @Test
    void theUpperBoundaryStillPaysOnePercentAndNoSurcharge() {
        assertEquals(Money.czk(100.00), policy.compute(Money.czk(10_000.00)),
                "10 000.00 belongs to the one percent tier, so no surcharge is added to it");
    }

    // -------------------------------------------------------------------------
    // Above 10 000.00: one percent plus the flat surcharge
    // -------------------------------------------------------------------------

    @Test
    void oneHellerPastTheUpperBoundaryAddsTheFlatSurcharge() {
        assertEquals(Money.czk(125.00), policy.compute(Money.czk(10_000.01)));
    }

    @Test
    void theSurchargeIsAddedOnceHoweverLargeTheAmount() {
        assertEquals(Money.czk(145.00), policy.compute(Money.czk(12_000)));
        assertEquals(Money.czk(325.00), policy.compute(Money.czk(30_000)));
    }

    // -------------------------------------------------------------------------
    // What the schedule costs the customer, which is the reason it is written down
    // -------------------------------------------------------------------------

    /**
     * The two discontinuities, stated as what the customer pays rather than as what the policy
     * returns. A step function on the whole amount means the total leaving the account jumps by
     * more than the extra heller that caused it, at both boundaries. Marginal brackets would
     * remove the first jump and, unless the surcharge went with them, keep the second.
     */
    @Test
    void bothBoundariesAreDiscontinuities() {
        assertEquals(Money.czk(10.01),
                totalOutlay(1_000.01).minus(totalOutlay(1_000.00)),
                "one more heller of value costs 10.01 more in total at the free boundary");

        assertEquals(Money.czk(25.01),
                totalOutlay(10_000.01).minus(totalOutlay(10_000.00)),
                "one more heller of value costs 25.01 more in total at the upper boundary");
    }

    // -------------------------------------------------------------------------
    // The currency the whole schedule is written in
    // -------------------------------------------------------------------------

    /**
     * The refusal must be the named domain exception and not the bare
     * {@code IllegalArgumentException("Currency mismatch")} that {@code Money.compareTo} would
     * raise two statements later. Both halves matter: the type says this is a stored-data problem
     * rather than the caller's, and it is the difference between a 500 the error contract knows
     * about and logs as inconsistent data, and one that falls through to the catch-all as
     * "Unhandled error".
     */
    @Test
    void aForeignAmountIsRefusedAsCorruptDataAndNotAsBadInput() {
        Money eur = Money.of("EUR", new BigDecimal("2000.00"));

        DataIntegrityException thrown =
                assertThrows(DataIntegrityException.class, () -> policy.compute(eur));

        assertTrue(thrown.getMessage().contains("EUR"),
                "the message must name the currency that was found, not only the one expected");
    }

    /**
     * The guard covers the whole schedule and not just the tier that happens to compare first.
     * A foreign amount below the free boundary would otherwise return CZK 0.00 without ever
     * reaching a comparison, and a policy that answers for an amount it cannot read is worse
     * than one that refuses.
     */
    @Test
    void aForeignAmountIsRefusedInEveryTierIncludingTheFreeOne() {
        for (String value : new String[] { "500.00", "2000.00", "30000.00" }) {
            Money eur = Money.of("EUR", new BigDecimal(value));
            assertThrows(DataIntegrityException.class, () -> policy.compute(eur),
                    "EUR " + value + " must be refused whichever tier it falls in");
        }
    }

    @Test
    void theFeeIsAlwaysInCrowns() {
        assertEquals("CZK", policy.compute(Money.czk(500)).currency());
        assertEquals("CZK", policy.compute(Money.czk(5_000)).currency());
        assertEquals("CZK", policy.compute(Money.czk(50_000)).currency());
    }

    /** What actually leaves the account: the amount plus the fee charged on it. */
    private Money totalOutlay(double amount) {
        Money money = Money.czk(amount);
        return money.plus(policy.compute(money));
    }
}
