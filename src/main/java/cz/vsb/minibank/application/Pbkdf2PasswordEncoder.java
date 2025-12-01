package cz.vsb.minibank.application;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

public class Pbkdf2PasswordEncoder implements PasswordEncoder {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;

    private final SecureRandom random = new SecureRandom();

    @Override
    public byte[] hash(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        } finally {
            spec.clearPassword();
        }
    }

    @Override
    public boolean matches(char[] rawPassword, byte[] salt, byte[] expectedHash) {
        byte[] actual = hash(rawPassword, salt);
        if (actual.length != expectedHash.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < actual.length; i++) {
            result |= actual[i] ^ expectedHash[i];
        }
        return result == 0;
    }

    @Override
    public byte[] generateSalt() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return salt;
    }
}
