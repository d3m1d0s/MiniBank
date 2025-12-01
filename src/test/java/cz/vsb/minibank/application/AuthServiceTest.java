package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.exceptions.AuthorizationFailedException;
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
    void loginWithWrongPasswordThrowsAuthorizationFailed() {
        PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        InMemoryUserRepository users = new InMemoryUserRepository();

        byte[] salt = encoder.generateSalt();
        byte[] hash = encoder.hash("secret123".toCharArray(), salt);

        int userId = users.nextId();
        User u = new User(userId, "alice", hash, salt, UserRole.CUSTOMER, 42);
        users.save(u);

        AuthService auth = new AuthService(users, encoder);

        assertThrows(AuthorizationFailedException.class, () ->
                auth.login("alice", "wrongPassword".toCharArray()));
    }

    @Test
    void loginWithUnknownUsernameThrowsAuthorizationFailed() {
        PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        InMemoryUserRepository users = new InMemoryUserRepository();

        AuthService auth = new AuthService(users, encoder);

        assertThrows(AuthorizationFailedException.class, () ->
                auth.login("no_such_user", "anything".toCharArray()));
    }
}
