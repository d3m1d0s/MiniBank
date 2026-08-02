package cz.vsb.minibank.domain.value;

import cz.vsb.minibank.domain.exceptions.InvalidIbanException;

import java.util.Locale;
import java.util.Objects;

/**
 * Value object representing a normalized Czech IBAN.
 *
 * Only CZ accounts are accepted, which is what this class has always required; what is new
 * is that the check digits are verified rather than assumed. A Czech IBAN is CZ, two check
 * digits and twenty more digits: twenty four characters in total.
 */
public final class IBAN {

    /** CZ, two check digits, then twenty digits of bank code and account number. */
    private static final int CZ_LENGTH = 24;

    /**
     * Normalized IBAN value without spaces and in upper case.
     */
    private final String value;

    /**
     * Creates and validates an IBAN from raw user input.
     *
     * @param raw raw IBAN string, possibly with spaces and mixed case
     * @throws InvalidIbanException when the format or the check digits are wrong
     */
    public IBAN(String raw) {
        Objects.requireNonNull(raw);
        String s = raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);

        if (!s.startsWith("CZ") || s.length() != CZ_LENGTH) {
            throw new InvalidIbanException("Invalid IBAN format: " + raw);
        }
        for (int i = 2; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new InvalidIbanException("Invalid IBAN format: " + raw);
            }
        }
        if (checksum(s) != 1) {
            throw new InvalidIbanException("Invalid IBAN check digits: " + raw);
        }

        this.value = s;
    }

    /**
     * ISO 7064 MOD 97-10: move the first four characters to the end, replace each letter by
     * its position in the alphabet plus nine, and take the remainder modulo 97, which is 1
     * for a valid IBAN. The remainder is accumulated character by character because the
     * expanded number is far larger than a long can hold.
     */
    private static int checksum(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int remainder = 0;
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            if (c >= '0' && c <= '9') {
                remainder = (remainder * 10 + (c - '0')) % 97;
            } else {
                remainder = (remainder * 100 + (c - 'A' + 10)) % 97;
            }
        }
        return remainder;
    }

    /**
     * Returns the normalized IBAN value.
     */
    public String value() { return value; }

    /**
     * Two IBANs are equal when their normalized values match, so spacing and case do not
     * matter. Without this, comparing a target IBAN against an account's own compiles,
     * always answers false, and silently lets a self transfer through.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IBAN other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() { return value; }
}
