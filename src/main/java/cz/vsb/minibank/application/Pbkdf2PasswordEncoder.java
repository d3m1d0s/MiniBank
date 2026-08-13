package cz.vsb.minibank.application;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

/**
 * PasswordEncoder implementation based on PBKDF2 with HMAC SHA-512.
 *
 * <h2>Why 220 000 and not 600 000</h2>
 *
 * The OWASP Password Storage Cheat Sheet gives PBKDF2 one iteration count per PRF:
 * HMAC-SHA256 at 600 000, HMAC-SHA512 at 220 000, and HMAC-SHA1 at 1 400 000 for legacy use
 * only. This class is on the SHA-512 row. Anybody who greps for 600000, finds 220000 here and
 * concludes the project is behind the recommendation should read the rest of this paragraph:
 * 220 000 <i>is</i> the recommendation, for this PRF.
 *
 * The cheat sheet marks the SHA-256 row recommended, on the grounds that HMAC-SHA-256 is
 * widely supported and is what NIST names; it does not say how the three counts compare with
 * each other, so this row was checked against the attacker rather than assumed equivalent. On
 * an RTX 4090, hashcat runs PBKDF2-HMAC-SHA256 at 8 865.7 kH/s and PBKDF2-HMAC-SHA512 at
 * 3 120.9 kH/s, both benchmarked at 999 iterations: a 2.84x penalty per iteration, where the
 * ratio 600000/220000 asks only for 2.73x. So this row is, if anything, marginally the dearer
 * of the two to attack, and the reason the other one is recommended is availability, not
 * strength.
 *
 * It is also far cheaper to defend with, which is what settled the choice. Measured on the
 * development machine, one hash costs about 0.7 s here, against about 1.6 s on the SHA-256
 * row and about 0.3 s at the 120 000 SHA-256 iterations this replaced. That machine is noisy
 * enough that single hashes ran from 0.6 s to just over a second, so those three are medians
 * and not a bound; the ordering between them is the durable part. Those three also decide the
 * row against the cheat sheet's other constraint, a hash inside one second for the defender:
 * the SHA-256 row does not clear it on this hardware and this one does.
 *
 * The stored format does not move at all. PBKDF2's output length is a parameter of the
 * function rather than a property of the PRF, so the hash stays 32 bytes and the salt 16, and
 * the stand-in hash AuthService compares for an unknown username still matches a real one by
 * length. What does move is allocation, because the PRF hands back a fresh digest on every
 * iteration and SHA-512's is 64 bytes where SHA-256's is 32: 80 bytes per iteration against
 * 48 once the array header is counted, measured at 17 601 992 bytes for one sign-in where the
 * old parameters cost 5 761 704. Seventeen megabytes of garbage per attempt rather than five,
 * which the login path quotes in two places and which moved with this.
 */
public class Pbkdf2PasswordEncoder implements PasswordEncoder {

    /**
     * <b>Changing this, or {@link #ITERATIONS}, makes every password already stored
     * unverifiable.</b>
     *
     * Neither value is written beside the hash: the users table holds password_hash and
     * password_salt and nothing else, and both of these are compile-time constants. So a row
     * hashed under one setting can never be checked under another, there is no per-row
     * parameter to fall back on, and nothing rehashes a password on a successful sign-in.
     *
     * That would be a migration note rather than a warning if the seeders overwrote what they
     * find, and they do not: AppSql.ensureDemoUsers and DemoUsersInitializer.ensureDemoUsers
     * both write a login only when findByUsername comes back empty. A Postgres database that
     * already holds alice and fraud therefore keeps its old rows, startup says nothing, and
     * the right password comes back 401 AUTH_FAILED, "The username or password is not
     * correct." - indistinguishable from a typo, and ten attempts at it spend the
     * LoginThrottle allowance for the window. Only the SQL backend can reach this. The only
     * other one this project has is json, and Bootstrap hands that an InMemoryUserRepository,
     * so its logins are rebuilt by the seeder on every start.
     *
     * The recovery is to make the rows missing again - {@code DELETE FROM users} against the
     * SQL backend, or drop the Postgres volume so db/init/schema.sql runs afresh - and restart
     * under the demo profile, which reseeds both logins with the new parameters. A deployment
     * with real customers in it would instead have to store the algorithm and the count per
     * row and rehash on the next successful login; this project has neither, deliberately, and
     * that is the boundary being noted here rather than a defect to fix.
     */
    private static final String ALGORITHM = "PBKDF2WithHmacSHA512";

    /** The count OWASP gives for that PRF. The warning on {@link #ALGORITHM} covers this too. */
    private static final int ITERATIONS = 220_000;

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
