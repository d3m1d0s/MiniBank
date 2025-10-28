package cz.vsb.minibank.domain.value;


import java.util.Locale;
import java.util.Objects;


public final class IBAN {
    private final String value; // normalized


    public IBAN(String raw) {
        Objects.requireNonNull(raw);
        String s = raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!s.startsWith("CZ") || s.length() < 10 || s.length() > 34)
            throw new IllegalArgumentException("Invalid IBAN format");
        this.value = s;
    }


    public String value() { return value; }
    @Override public String toString() { return value; }
}