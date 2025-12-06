package cz.vsb.minibank.domain.value;

import cz.vsb.minibank.domain.exceptions.InvalidIbanException;

import java.util.Locale;
import java.util.Objects;

/**
 * Value object representing a normalized IBAN.
 */
public final class IBAN {

    /**
     * Normalized IBAN value without spaces and in upper case.
     */
    private final String value;

    /**
     * Creates and validates an IBAN from raw user input.
     *
     * @param raw raw IBAN string, possibly with spaces and mixed case
     * @throws InvalidIbanException when the format is not supported
     */
    public IBAN(String raw) {
        Objects.requireNonNull(raw);
        String s = raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);

        if (!s.startsWith("CZ") || s.length() < 10 || s.length() > 34) {
            throw new InvalidIbanException("Invalid IBAN format: " + raw);
        }

        this.value = s;
    }

    /**
     * Returns the normalized IBAN value.
     */
    public String value() { return value; }

    @Override
    public String toString() { return value; }
}
