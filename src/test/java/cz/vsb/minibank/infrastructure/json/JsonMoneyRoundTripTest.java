package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.json.dto.JsonAccount;
import cz.vsb.minibank.infrastructure.json.dto.JsonTransfer;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the JSON store keeps of a money value, now that it keeps a BigDecimal rather than a double.
 *
 * The store held money as {@code double} until this change, and that was exact for every amount
 * the bank can hold: a heller-by-heller round trip from 0.01 to 100 000.00 loses nothing, and the
 * first value that does lose is four orders of magnitude past what {@code NUMERIC(14,2)} on the
 * SQL side accepts. So this class is not here to catch arithmetic drift that was happening.
 *
 * It is here for the property a primitive could not express. A {@code double} field has no absent
 * value, so a stored row missing its amount deserialized to 0.00 with nothing anywhere saying so -
 * a balance of nothing, or a payment of nothing, arriving as a fact rather than as a fault.
 */
class JsonMoneyRoundTripTest {

    private static final String IBAN_VALUE = "CZ6508000000192000145399";

    // -------------------------------------------------------------------------
    // The value survives, exactly
    // -------------------------------------------------------------------------

    @Test
    void anAccountKeepsItsExactBalanceAndLimitsThroughTheRoundTrip() {
        Account before = new Account(7, new IBAN(IBAN_VALUE),
                Money.czk(new BigDecimal("12345.67")),
                Money.czk(new BigDecimal("40000.00")),
                Money.czk(new BigDecimal("3000.00")));

        Account after = JsonMapper.toDomain(JsonMapper.toDto(before));

        assertEquals(before.balance(), after.balance());
        assertEquals(before.dailyLimit(), after.dailyLimit());
        assertEquals(before.softDailyThreshold(), after.softDailyThreshold());
    }

    @Test
    void everyHellerOfADifficultAmountSurvives() {
        for (String value : new String[] { "0.01", "0.07", "1.15", "99.99", "1000.01", "8765.43" }) {
            JsonTransfer dto = row(11, new BigDecimal(value));

            assertEquals(Money.czk(new BigDecimal(value)), JsonMapper.toDomain(dto).amount(),
                    value + " must survive the store unchanged");
        }
    }

    /**
     * The distinction the fee field is nullable for. A settled transfer charged nothing and one
     * that has not settled are different facts, and collapsing them would make
     * {@code Transfer.feeFor} re-quote a fee that was really zero.
     */
    @Test
    void aZeroFeeAndAnAbsentFeeStayDifferent() {
        JsonTransfer charged = row(12, new BigDecimal("500.00"));
        charged.fee = new BigDecimal("0.00");
        charged.status = "SENT";

        JsonTransfer notYet = row(13, new BigDecimal("500.00"));
        notYet.fee = null;

        assertEquals(Money.czk(0.00), JsonMapper.toDomain(charged).fee());
        assertNull(JsonMapper.toDomain(notYet).fee());
    }

    // -------------------------------------------------------------------------
    // Absent is now a value the field can hold, and it is refused
    // -------------------------------------------------------------------------

    @Test
    void anAccountWithNoStoredBalanceIsRefusedRatherThanReadAsZero() {
        JsonAccount dto = new JsonAccount();
        dto.id = 42;
        dto.iban = IBAN_VALUE;
        dto.balance = null;
        dto.dailyLimit = new BigDecimal("40000.00");

        DataIntegrityException thrown =
                assertThrows(DataIntegrityException.class, () -> JsonMapper.toDomain(dto));
        assertTrue(thrown.getMessage().contains("42"), "the refusal must name the row");
        assertTrue(thrown.getMessage().contains("balance"), "and the field that is missing");
    }

    @Test
    void anAccountWithNoStoredDailyLimitIsRefused() {
        JsonAccount dto = new JsonAccount();
        dto.id = 43;
        dto.iban = IBAN_VALUE;
        dto.balance = new BigDecimal("100.00");
        dto.dailyLimit = null;

        assertThrows(DataIntegrityException.class, () -> JsonMapper.toDomain(dto));
    }

    /**
     * An absent soft threshold is not the same defect. Null there means "use the bank-wide tier",
     * which is the ordinary case, and a stored 0.00 would mean the opposite rule - that every
     * payment crosses it.
     */
    @Test
    void anAbsentSoftThresholdIsAMeaningAndNotACorruptRow() {
        JsonAccount dto = new JsonAccount();
        dto.id = 44;
        dto.iban = IBAN_VALUE;
        dto.balance = new BigDecimal("100.00");
        dto.dailyLimit = new BigDecimal("40000.00");
        dto.softDailyThreshold = null;

        Account account = JsonMapper.toDomain(dto);

        assertNull(account.softDailyThreshold(),
                "no override must stay no override, not become a zero tier");
    }

    private static JsonTransfer row(int id, BigDecimal amount) {
        JsonTransfer dto = new JsonTransfer();
        dto.id = id;
        dto.sourceAccountId = 1;
        dto.targetIbanSnapshot = IBAN_VALUE;
        dto.amount = amount;
        dto.currency = "CZK";
        dto.status = "CREATED";
        return dto;
    }
}
