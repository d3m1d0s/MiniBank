package cz.vsb.minibank.application.auth;

import cz.vsb.minibank.domain.customer.User;
import cz.vsb.minibank.domain.customer.UserRole;
import cz.vsb.minibank.domain.exceptions.AuthenticationFailedException;
import cz.vsb.minibank.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {

    /**
     * Simple in-memory implementation of UserRepository for unit tests.
     */
    private static class InMemoryUserRepository implements UserRepository {

        private final Map<Integer, User> byId = new HashMap<>();
        private final Map<String, User> byUsername = new HashMap<>();
        private int seq = 1;

        @Override
        public Optional<User> byId(int id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<User> findByUsername(String username) {
            return Optional.ofNullable(byUsername.get(username));
        }

        @Override
        public void save(User user) {
            byId.put(user.id(), user);
            byUsername.put(user.username(), user);
        }

        @Override
        public int nextId() {
            return seq++;
        }
    }

    @Test
    void loginWithCorrectPasswordSucceeds() {
        PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        InMemoryUserRepository users = new InMemoryUserRepository();

        byte[] salt = encoder.generateSalt();
        byte[] hash = encoder.hash("secret123".toCharArray(), salt);

        int userId = users.nextId();
        User u = new User(userId, "alice", hash, salt, UserRole.CUSTOMER, 42);
        users.save(u);

        AuthService auth = new AuthService(users, encoder);

        User loggedIn = auth.login("alice", "secret123".toCharArray());

        assertNotNull(loggedIn);
        assertEquals("alice", loggedIn.username());
        assertEquals(UserRole.CUSTOMER, loggedIn.role());
        assertEquals(42, loggedIn.customerId());
    }

    @Test
    void loginWithWrongPasswordThrowsAuthenticationFailed() {
        PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        InMemoryUserRepository users = new InMemoryUserRepository();

        byte[] salt = encoder.generateSalt();
        byte[] hash = encoder.hash("secret123".toCharArray(), salt);

        int userId = users.nextId();
        User u = new User(userId, "alice", hash, salt, UserRole.CUSTOMER, 42);
        users.save(u);

        AuthService auth = new AuthService(users, encoder);

        assertThrows(AuthenticationFailedException.class, () ->
                auth.login("alice", "wrongPassword".toCharArray()));
    }

    @Test
    void loginWithUnknownUsernameThrowsAuthenticationFailed() {
        PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        InMemoryUserRepository users = new InMemoryUserRepository();

        AuthService auth = new AuthService(users, encoder);

        assertThrows(AuthenticationFailedException.class, () ->
                auth.login("no_such_user", "anything".toCharArray()));
    }

    /**
     * Both login failures return the same status, the same code and the same message, so
     * the only channel left that could answer "does this username exist?" is how long the
     * request takes. PBKDF2-HMAC-SHA512 at 220 000 iterations is about 0.7 s, which is enough
     * to read off a single request, so the unknown-username branch must hash too.
     *
     * Asserted by counting calls rather than by timing them: a stopwatch assertion on a
     * shared CI machine is a flake generator, and the call count is the property that
     * actually has to hold.
     */
    @Test
    void loginWithUnknownUsernameStillPaysThePasswordHashingCost() {
        CountingEncoder encoder = new CountingEncoder();
        InMemoryUserRepository users = new InMemoryUserRepository();

        byte[] salt = encoder.generateSalt();
        User u = new User(users.nextId(), "alice", encoder.hash("secret123".toCharArray(), salt),
                salt, UserRole.CUSTOMER, 42);
        users.save(u);

        AuthService auth = new AuthService(users, encoder);

        encoder.matchCalls = 0;
        assertThrows(AuthenticationFailedException.class, () ->
                auth.login("alice", "wrongPassword".toCharArray()));
        int knownUsernameCost = encoder.matchCalls;

        encoder.matchCalls = 0;
        assertThrows(AuthenticationFailedException.class, () ->
                auth.login("no_such_user", "anything".toCharArray()));
        int unknownUsernameCost = encoder.matchCalls;

        assertEquals(knownUsernameCost, unknownUsernameCost,
                "An unknown username must cost the same number of password hashes as a wrong password");
        assertEquals(1, unknownUsernameCost);
    }

    /**
     * Counts matches() calls, and refuses a zero-length salt the way PBEKeySpec does, so
     * the stand-in salt AuthService uses stays a valid one.
     */
    private static class CountingEncoder implements PasswordEncoder {

        int matchCalls;

        @Override
        public byte[] hash(char[] password, byte[] salt) {
            if (salt == null || salt.length == 0) {
                throw new IllegalArgumentException("Salt must not be empty");
            }
            byte[] out = new byte[32];
            out[0] = (byte) new String(password).hashCode();
            return out;
        }

        @Override
        public boolean matches(char[] rawPassword, byte[] salt, byte[] expectedHash) {
            matchCalls++;
            return java.util.Arrays.equals(hash(rawPassword, salt), expectedHash);
        }

        @Override
        public byte[] generateSalt() {
            return new byte[16];
        }
    }
}
