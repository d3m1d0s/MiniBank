package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.json.dto.JsonTransfer;
import cz.vsb.minibank.infrastructure.json.repo.JsonTransferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the day's outflow does with a stored row this application would refuse to load.
 *
 * The daily total is the one place a stored transfer is read without a Transfer being built
 * around it: it sums the DTOs, deliberately, because building an aggregate and two lazy closures
 * per row to read one number off each is what the aggregate query exists to avoid. The cost of
 * that shortcut is that every rule the loader states has to be restated here, and it was not.
 * Only the absent amount was, so a row with a negative amount, a row in another currency and a
 * row whose timestamp will not parse each reached the total by a route of its own - the first
 * counted with a minus sign, the other two dropped without a word.
 *
 * A dropped row and a negative one are not the same size of mistake. Dropping one loses a payment
 * from the total, which is already the defect the ceiling exists to prevent; a negative one
 * reduces the total below what the account really spent, so it hands the next real payment
 * headroom it has not earned.
 *
 * These are JSON-only properties. The SQL backend keeps CHECK constraints on amount and currency
 * and a timestamp column that cannot hold a string, so no such row can be written there at all,
 * and the sum runs in the database.
 *
 * IT IS ASKED THROUGH sentTotalBetween, and that is now a default on the interface over the
 * customer-wide aggregate rather than a query of its own - one account, nothing excluded. Nothing
 * here changed when the ceiling moved to the customer, and the reason to say so is that this class
 * has become the only place the ROW-LEVEL rules of that aggregate are pinned: which rows count,
 * which timestamp decides the window, what a malformed row does. The rules the customer-wide form
 * adds on top of these - which accounts, and which destinations do not count as leaving - are
 * pinned in OwnAccountTransfersDoNotSpendTheDayTest, where there is a customer to hang them on.
 */
class JsonTransferSumRefusalTest {

    @TempDir
    Path tempDir;

    private static final int ACCOUNT = 100;
    private static final String PAYEE = "CZ6508000000192000145399";

    /** A second account of the same customer, which is what the exclusion below is given. */
    private static final String OWN_SECOND_IBAN = "CZ4308000000192000145407";

    private static final Instant DAY_START = Instant.parse("2026-03-04T00:00:00Z");
    private static final Instant NEXT_DAY = Instant.parse("2026-03-05T00:00:00Z");
    private static final String IN_THE_DAY = "2026-03-04T10:15:00Z";
    private static final String THE_DAY_BEFORE = "2026-03-03T10:15:00Z";

    // -------------------------------------------------------------------------
    // What the total is when every row is one the loader would accept
    // -------------------------------------------------------------------------

    @Test
    void theDaysOutflowIsTheSumOfTheRowsThatSettledInIt() throws Exception {
        JsonTransfer inside = sent(5001, "1000.00");
        JsonTransfer alsoInside = sent(5002, "250.00");
        JsonTransfer before = sent(5003, "9000.00");
        before.settledAt = THE_DAY_BEFORE;
        before.createdAt = THE_DAY_BEFORE;

        JsonTransferRepository transfers = repositoryOver(inside, alsoInside, before);

        assertEquals(Money.czk(new BigDecimal("1250.00")),
                transfers.sentTotalBetween(ACCOUNT, DAY_START, NEXT_DAY),
                "only what settled inside the window counts, and all of it does");
    }

    // -------------------------------------------------------------------------
    // The amount
    // -------------------------------------------------------------------------

    /**
     * The direction that costs money. Money fixes a scale and a currency and says nothing about
     * sign, so a hand-edited negative row was added as it stood and took the day's outflow down
     * with it - 1000.00 spent and 600.00 reported, which is 400.00 of ceiling the account can
     * spend twice.
     */
    @Test
    void aNegativeAmountRefusesTheDayRatherThanSubtractingFromIt() throws Exception {
        JsonTransferRepository transfers =
                repositoryOver(sent(5001, "1000.00"), sent(5002, "-400.00"));

        DataIntegrityException refusal = assertThrows(DataIntegrityException.class,
                () -> transfers.sentTotalBetween(ACCOUNT, DAY_START, NEXT_DAY));

        assertTrue(refusal.getMessage().contains("5002"), "the refusal must name the row");
        assertTrue(refusal.getMessage().contains("-400.00"), "and the value it refused");
    }

    /**
     * Zero is refused for the same reason and not a stricter one: Transfer's constructor requires
     * a strictly positive amount, so this row is one {@code byId} would not hand out either. It
     * costs the total nothing, which is exactly why it needs pinning - a rule that only fires
     * where the arithmetic already hurts is not the rule the loader applies.
     */
    @Test
    void anAmountOfZeroIsRefusedBecauseTheLoaderRefusesItToo() throws Exception {
        JsonTransferRepository transfers =
                repositoryOver(sent(5001, "1000.00"), sent(5002, "0.00"));

        assertThrows(DataIntegrityException.class,
                () -> transfers.sentTotalBetween(ACCOUNT, DAY_START, NEXT_DAY));
    }

    // -------------------------------------------------------------------------
    // The currency
    // -------------------------------------------------------------------------

    /**
     * Every one of these is refused on the load path - Money's constructor rejects a code that is
     * not three upper case letters, Transfer's rejects a well-formed foreign one - so a total
     * that skipped them answered a number that no statement of the same rows adds up to.
     */
    @Test
    void aCurrencyThisBankDoesNotKeepRefusesTheDayRatherThanBeingSkipped() throws Exception {
        for (String stored : new String[] { "USD", "czk", "" }) {
            JsonTransfer foreign = sent(5002, "700.00");
            foreign.currency = stored;

            JsonTransferRepository transfers = repositoryOver(sent(5001, "1000.00"), foreign);

            DataIntegrityException refusal = assertThrows(DataIntegrityException.class,
                    () -> transfers.sentTotalBetween(ACCOUNT, DAY_START, NEXT_DAY),
                    "a row stored as '" + stored + "' must not be quietly dropped");
            assertTrue(refusal.getMessage().contains("5002"), "the refusal must name the row");
        }
    }

    @Test
    void aRowWithNoStoredCurrencyIsRefusedAsWell() throws Exception {
        JsonTransfer unstated = sent(5002, "700.00");
        unstated.currency = null;

        JsonTransferRepository transfers = repositoryOver(sent(5001, "1000.00"), unstated);

        assertThrows(DataIntegrityException.class,
                () -> transfers.sentTotalBetween(ACCOUNT, DAY_START, NEXT_DAY));
    }

    // -------------------------------------------------------------------------
    // The timestamps: present and unreadable is refused, absent is not
    // -------------------------------------------------------------------------

    /**
     * The settlement instant used to fall through to the creation one whenever it would not
     * parse, so this row was counted under a day chosen by the failure rather than by the row.
     */
    @Test
    void aPresentButUnreadableSettlementInstantRefusesTheDay() throws Exception {
        JsonTransfer garbled = sent(5002, "700.00");
        garbled.settledAt = "yesterday afternoon";

        JsonTransferRepository transfers = repositoryOver(sent(5001, "1000.00"), garbled);

        DataIntegrityException refusal = assertThrows(DataIntegrityException.class,
                () -> transfers.sentTotalBetween(ACCOUNT, DAY_START, NEXT_DAY));

        assertTrue(refusal.getMessage().contains("5002"), "the refusal must name the row");
        assertTrue(refusal.getMessage().contains("yesterday afternoon"),
                "and the value it could not read");
    }

    /**
     * With nothing to fall back to, the same row simply vanished from the total. A date with no
     * time of day is the shape a hand edit takes, and it is not an Instant.
     */
    @Test
    void aPresentButUnreadableCreationInstantRefusesTheDay() throws Exception {
        JsonTransfer garbled = sent(5002, "700.00");
        garbled.settledAt = null;
        garbled.createdAt = "2026-03-04";

        JsonTransferRepository transfers = repositoryOver(sent(5001, "1000.00"), garbled);

        DataIntegrityException refusal = assertThrows(DataIntegrityException.class,
                () -> transfers.sentTotalBetween(ACCOUNT, DAY_START, NEXT_DAY));

        assertTrue(refusal.getMessage().contains("5002"), "the refusal must name the row");
    }

    /**
     * The line the refusal must not cross. A transfer that has not settled carries no settlement
     * instant, and a row written before the column existed carries none either: absent is the
     * fact this store is meant to hold, and the creation day is what such a row has always
     * counted under. Both backends state that rule, and refusing here would make the JSON one
     * reject rows the SQL one sums.
     */
    @Test
    void anAbsentSettlementInstantStillCountsUnderTheCreationDay() throws Exception {
        JsonTransfer unsettledColumn = sent(5001, "250.00");
        unsettledColumn.settledAt = null;

        JsonTransfer older = sent(5002, "9000.00");
        older.settledAt = null;
        older.createdAt = THE_DAY_BEFORE;

        JsonTransferRepository transfers = repositoryOver(unsettledColumn, older);

        assertEquals(Money.czk(new BigDecimal("250.00")),
                transfers.sentTotalBetween(ACCOUNT, DAY_START, NEXT_DAY),
                "a row with no settlement instant counts under the day it was created");
    }

    /**
     * The other half of that line. A row carrying neither timestamp counts toward no day at all,
     * which is what the NULL that fails both range comparisons does on the SQL backend. It is the
     * one case where this total is deliberately more forgiving than the loader.
     */
    @Test
    void aRowWithNeitherTimestampCountsTowardNoDayAtAll() throws Exception {
        JsonTransfer undated = sent(5002, "9000.00");
        undated.settledAt = null;
        undated.createdAt = null;

        JsonTransferRepository transfers = repositoryOver(sent(5001, "100.00"), undated);

        assertEquals(Money.czk(new BigDecimal("100.00")),
                transfers.sentTotalBetween(ACCOUNT, DAY_START, NEXT_DAY),
                "no timestamp at all is no day, not a refusal");
    }

    // -------------------------------------------------------------------------
    // The destination: which rows the customer-wide total drops
    // -------------------------------------------------------------------------

    /**
     * The exclusion is by IBAN and it is compared normalized, because a Transfer takes its
     * destination snapshot as a plain String and nothing on the way in puts it in one spelling.
     *
     * A stored 'cz43 0800 0000 1920 0014 5407' and a customer's own CZ4308000000192000145407 are
     * one account written two ways. Compared as they stand they are two, and the customer's own
     * money moved in place would be counted as having left them - which is precisely the inflation
     * the exclusion exists to prevent, arriving through a spelling instead of through a rule.
     */
    @Test
    void anOwnIbanStoredInAnyCasingOrSpacingIsStillExcluded() throws Exception {
        for (String spelling : new String[] {
                OWN_SECOND_IBAN,
                OWN_SECOND_IBAN.toLowerCase(java.util.Locale.ROOT),
                "cz43 0800 0000 1920 0014 5407" }) {
            JsonTransfer inward = sent(5002, "9000.00");
            inward.targetIbanSnapshot = spelling;

            JsonTransferRepository transfers = repositoryOver(sent(5001, "1000.00"), inward);

            assertEquals(Money.czk(new BigDecimal("1000.00")),
                    transfers.sentTotalLeavingCustomerBetween(
                            List.of(ACCOUNT), List.of(OWN_SECOND_IBAN), DAY_START, NEXT_DAY),
                    "a row landing on the customer's own account, stored as '" + spelling
                            + "', has not left them");
        }
    }

    /**
     * The exclusion list is spelled loosely too, and for the same reason: it is built from whatever
     * the customer's account rows hold, and comparing one normalized side against one raw side is a
     * predicate that answers correctly only by luck.
     */
    @Test
    void theExclusionListIsNormalizedAsWellAsTheStoredSnapshot() throws Exception {
        JsonTransfer inward = sent(5002, "9000.00");
        inward.targetIbanSnapshot = OWN_SECOND_IBAN;

        JsonTransferRepository transfers = repositoryOver(sent(5001, "1000.00"), inward);

        assertEquals(Money.czk(new BigDecimal("1000.00")),
                transfers.sentTotalLeavingCustomerBetween(
                        List.of(ACCOUNT), List.of("cz43 0800 0000 1920 0014 5407"),
                        DAY_START, NEXT_DAY),
                "however the caller spells its own IBAN, it names the same account");
    }

    /**
     * A row this store cannot say the destination of is COUNTED, and that is the safe direction.
     *
     * The snapshot is NOT NULL on the SQL backend only, so this row exists here alone. Treating
     * "no destination" as "no exclusion matched" costs at most a day total that is too high, and
     * the customer is refused sooner than they need be; treating it as an internal move would let
     * a row with the field cleared spend nothing at all, which is a way past the ceiling that
     * needs no more than a text editor.
     */
    @Test
    void aRowWithNoStoredDestinationIsCountedRatherThanTakenForAnInternalMove() throws Exception {
        JsonTransfer unaddressed = sent(5002, "9000.00");
        unaddressed.targetIbanSnapshot = null;

        JsonTransferRepository transfers = repositoryOver(sent(5001, "1000.00"), unaddressed);

        assertEquals(Money.czk(new BigDecimal("10000.00")),
                transfers.sentTotalLeavingCustomerBetween(
                        List.of(ACCOUNT), List.of(OWN_SECOND_IBAN), DAY_START, NEXT_DAY),
                "a missing destination matches no exclusion, so the money counts as gone");
    }

    /**
     * The two degenerate collections, stated because one of them is what makes
     * {@code sentTotalBetween} a default over this method rather than a query of its own.
     */
    @Test
    void noAccountsIsZeroAndNoExclusionsDropsNothing() throws Exception {
        JsonTransfer inward = sent(5002, "9000.00");
        inward.targetIbanSnapshot = OWN_SECOND_IBAN;

        JsonTransferRepository transfers = repositoryOver(sent(5001, "1000.00"), inward);

        assertEquals(Money.czk(new BigDecimal("0.00")),
                transfers.sentTotalLeavingCustomerBetween(
                        List.of(), List.of(OWN_SECOND_IBAN), DAY_START, NEXT_DAY),
                "a customer holding no account has sent nothing");

        assertEquals(Money.czk(new BigDecimal("10000.00")),
                transfers.sentTotalLeavingCustomerBetween(
                        List.of(ACCOUNT), List.of(), DAY_START, NEXT_DAY),
                "and with nothing to exclude this is the plain per-account total");
    }

    // -------------------------------------------------------------------------
    // The same rule on the total narrowed to one payee
    // -------------------------------------------------------------------------

    @Test
    void theTotalNarrowedToOnePayeeRefusesTheSameRows() throws Exception {
        JsonTransferRepository transfers =
                repositoryOver(sent(5001, "1000.00"), sent(5002, "-400.00"));

        DataIntegrityException refusal = assertThrows(DataIntegrityException.class,
                () -> transfers.sentTotalToIbanBetween(ACCOUNT, PAYEE, DAY_START, NEXT_DAY));

        assertTrue(refusal.getMessage().contains("5002"), "the refusal must name the row");
    }

    /**
     * And it still narrows: a row to somebody else is not this payee's business, and filtering it
     * out is not the silent drop this class is about.
     */
    @Test
    void theTotalNarrowedToOnePayeeStillLeavesOutTheOtherPayees() throws Exception {
        JsonTransfer elsewhere = sent(5002, "700.00");
        elsewhere.targetIbanSnapshot = "CZ4308000000192000145407";

        JsonTransferRepository transfers = repositoryOver(sent(5001, "1000.00"), elsewhere);

        assertEquals(Money.czk(new BigDecimal("1000.00")),
                transfers.sentTotalToIbanBetween(ACCOUNT, PAYEE, DAY_START, NEXT_DAY));
    }

    private JsonTransferRepository repositoryOver(JsonTransfer... rows) throws Exception {
        JsonDataStore store = new JsonDataStore(tempDir.resolve("data.json").toString());
        store.load();

        store.lock();
        try {
            for (JsonTransfer row : rows) {
                store.data().transfers.add(row);
            }
        } finally {
            store.unlock();
        }

        return new JsonTransferRepository(store);
    }

    /**
     * A settled row of the kind the application itself writes, so that every test below differs
     * from a loadable row in exactly the one field it is about.
     */
    private static JsonTransfer sent(int id, String amount) {
        JsonTransfer dto = new JsonTransfer();
        dto.id = id;
        dto.sourceAccountId = ACCOUNT;
        dto.targetIbanSnapshot = PAYEE;
        dto.amount = new BigDecimal(amount);
        dto.currency = "CZK";
        dto.status = "SENT";
        dto.createdAt = IN_THE_DAY;
        dto.settledAt = IN_THE_DAY;
        return dto;
    }
}
