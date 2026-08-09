package cz.vsb.minibank.domain.value;

import cz.vsb.minibank.domain.exceptions.InvalidIbanException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The IBAN value object: normalization, the check digits, and equality.
 */
class IbanTest {

    private static final String VALID = "CZ6508000000192000145399";

    @Test
    void aValidCzechIbanIsAccepted() {
        assertEquals(VALID, new IBAN(VALID).value());
    }

    @Test
    void spacingAndCaseAreNormalizedAway() {
        assertEquals(VALID, new IBAN("cz65 0800 0000 1920 0014 5399").value());
        assertEquals(VALID, new IBAN("  " + VALID + "  ").value());
    }

    /**
     * The check digits are what the validation previously assumed rather than verified.
     * Changing any single digit invalidates the whole number, which is the point of mod 97.
     */
    @Test
    void wrongCheckDigitsAreRejected() {
        assertThrows(InvalidIbanException.class, () -> new IBAN("CZ6408000000192000145399"));
        assertThrows(InvalidIbanException.class, () -> new IBAN("CZ0000000000000000000000"));
    }

    @Test
    void aChangedAccountDigitIsRejected() {
        String tampered = VALID.substring(0, 23) + "8";

        assertNotEquals(VALID, tampered);
        assertThrows(InvalidIbanException.class, () -> new IBAN(tampered));
    }

    @Test
    void onlyCzechIbansOfTheRightLengthAreAccepted() {
        assertThrows(InvalidIbanException.class, () -> new IBAN("SK6508000000192000145399"));
        assertThrows(InvalidIbanException.class, () -> new IBAN("CZ650800000019200014539"));
        assertThrows(InvalidIbanException.class, () -> new IBAN("CZ65080000001920001453999"));
        assertThrows(InvalidIbanException.class, () -> new IBAN(""));
    }

    @Test
    void nonDigitsAfterTheCountryCodeAreRejected() {
        assertThrows(InvalidIbanException.class, () -> new IBAN("CZ!!!!!!!!!!!!!!!!!!!!!!"));
        assertThrows(InvalidIbanException.class, () -> new IBAN("CZ6508000000192000145X99"));
    }

    @Test
    void aNullValueIsRejected() {
        assertThrows(NullPointerException.class, () -> new IBAN(null));
    }

    /**
     * Without equality a self-transfer guard written the obvious way compiles, always
     * answers false, and silently does nothing.
     */
    @Test
    void equalityIgnoresSpacingAndCase() {
        IBAN plain = new IBAN(VALID);
        IBAN formatted = new IBAN("cz65 0800 0000 1920 0014 5399");

        assertEquals(plain, formatted);
        assertEquals(plain.hashCode(), formatted.hashCode());
        assertNotEquals(plain, new IBAN("CZ4308000000192000145407"));
        assertNotEquals(plain, null);
        assertNotEquals(plain, VALID);
    }

    @Test
    void equalValuesCollapseInAHashSet() {
        Set<IBAN> set = new HashSet<>(List.of(
                new IBAN(VALID),
                new IBAN("cz65 0800 0000 1920 0014 5399"),
                new IBAN("CZ4308000000192000145407")
        ));

        assertEquals(2, set.size());
    }
}
