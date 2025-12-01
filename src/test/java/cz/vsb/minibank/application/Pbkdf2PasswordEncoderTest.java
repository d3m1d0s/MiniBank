package cz.vsb.minibank.application;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class Pbkdf2PasswordEncoderTest {

    @Test
    void samePasswordAndSaltProduceSameHash() {
        PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        byte[] salt = encoder.generateSalt();

        byte[] hash1 = encoder.hash("secret123".toCharArray(), salt);
        byte[] hash2 = encoder.hash("secret123".toCharArray(), salt);

        assertArrayEquals(hash1, hash2,
                "Hash must be deterministic for the same password and salt");
    }

    @Test
    void differentPasswordOrSaltProduceDifferentHash() {
        PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        byte[] salt1 = encoder.generateSalt();
        byte[] salt2 = encoder.generateSalt();

        byte[] hash1 = encoder.hash("secret123".toCharArray(), salt1);
        byte[] hash2 = encoder.hash("secret123".toCharArray(), salt2);
        byte[] hash3 = encoder.hash("otherPassword".toCharArray(), salt1);

        assertFalse(Arrays.equals(hash1, hash2),
                "Different salt should produce different hash");
        assertFalse(Arrays.equals(hash1, hash3),
                "Different password should produce different hash");
    }

    @Test
    void matchesReturnsTrueOnlyForCorrectPassword() {
        PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        byte[] salt = encoder.generateSalt();
        char[] correct = "secret123".toCharArray();
        char[] wrong = "wrong".toCharArray();

        byte[] hash = encoder.hash(correct, salt);

        assertTrue(encoder.matches(correct, salt, hash),
                "Correct password must match stored hash");
        assertFalse(encoder.matches(wrong, salt, hash),
                "Wrong password must not match stored hash");
    }
}
