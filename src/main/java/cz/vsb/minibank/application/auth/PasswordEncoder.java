package cz.vsb.minibank.application.auth;

/**
 * Abstraction for password hashing with salt.
 */
public interface PasswordEncoder {

    /**
     * Computes a password hash for the given password and salt.
     */
    byte[] hash(char[] password, byte[] salt);

    /**
     * Verifies that the raw password matches the expected hash for the given salt.
     */
    boolean matches(char[] rawPassword, byte[] salt, byte[] expectedHash);

    /**
     * Generates a new random salt for password hashing.
     */
    byte[] generateSalt();
}
