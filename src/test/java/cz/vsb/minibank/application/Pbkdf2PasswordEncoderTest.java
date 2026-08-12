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

    /**
     * The property that makes the PRF and the iteration count a free choice, which is the only
     * reason moving between the OWASP rows was a two-line change: PBKDF2 derives however many
     * bytes it is asked for, so neither the hash nor the salt takes its size from the hash
     * function underneath. Three other things are written against these two numbers - the
     * stand-in credential AuthService hashes for an unknown username, which has to match a
     * real one by length or the comparison after it stops costing the same; the BYTEA columns
     * in db/init/schema.sql; and SqlUserRepositoryTest, which round-trips exactly 32 and 16
     * bytes - so a change that moved either would break all of them somewhere further away
     * than here.
     *
     * Deliberately not a test of ITERATIONS or of the algorithm name. Asserting those would
     * only restate the constants, and would have to be edited by the same hand that edits
     * them, which is no check at all. Nor is it a test of how long a hash takes: a stopwatch
     * assertion on a shared machine is a flake generator, and the cost is a tuning decision
     * rather than a contract.
     */
    @Test
    void theStoredFormatDoesNotDependOnTheHashFunctionUnderneath() {
        PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        byte[] salt = encoder.generateSalt();

        byte[] hash = encoder.hash("secret123".toCharArray(), salt);

        assertEquals(16, salt.length, "the salt column and AuthService's stand-in are 16 bytes");
        assertEquals(32, hash.length, "the hash column and AuthService's stand-in are 32 bytes");
    }
}
