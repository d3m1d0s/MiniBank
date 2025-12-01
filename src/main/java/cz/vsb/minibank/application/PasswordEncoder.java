package cz.vsb.minibank.application;

public interface PasswordEncoder {
    byte[] hash(char[] password, byte[] salt);

    boolean matches(char[] rawPassword, byte[] salt, byte[] expectedHash);

    byte[] generateSalt();
}
