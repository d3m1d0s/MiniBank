package cz.vsb.minibank.application.auth;

import cz.vsb.minibank.domain.customer.User;
import cz.vsb.minibank.domain.repository.UserRepository;
import cz.vsb.minibank.domain.exceptions.AuthenticationFailedException;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Handles user authentication and integration with the security context.
 */
public class AuthService {

    /**
     * Stand-in credentials hashed when the username is unknown, so that both failures cost
     * the same. The salt must be non-empty: PBEKeySpec rejects a zero-length one. Sixteen
     * bytes matches PasswordEncoder.generateSalt, and the hash length matches the encoder's
     * output so the comparison after it does the same work too.
     */
    private static final byte[] DUMMY_SALT = new byte[16];
    private static final byte[] DUMMY_HASH = new byte[32];

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public AuthService(UserRepository users, PasswordEncoder encoder) {
        this.users = Objects.requireNonNull(users, "users");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
    }

    /**
     * Authenticates a user and stores the authenticated user in the security context.
     *
     * An unknown username and a wrong password are indistinguishable to the caller: same
     * exception, same message, and - because the unknown-username branch below hashes a
     * stand-in - very nearly the same response time. Skipping the hash there would leak
     * the user list through a single timed request, since PBKDF2-HMAC-SHA512 at 220 000
     * iterations takes about 0.7 s, which anybody can read off a stopwatch.
     *
     * @throws AuthenticationFailedException when username or password is invalid
     */
    public User login(String username, char[] password) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");

        try {
            Optional<User> found = users.findByUsername(username);
            if (found.isEmpty()) {
                // Result deliberately discarded; this call exists only for its cost.
                encoder.matches(password, DUMMY_SALT, DUMMY_HASH);
                throw new AuthenticationFailedException("Invalid username or password");
            }

            User user = found.get();
            boolean ok = encoder.matches(password, user.passwordSalt(), user.passwordHash());
            if (!ok) {
                throw new AuthenticationFailedException("Invalid username or password");
            }

            SecurityContext.setCurrentUser(user);
            return user;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * Clears the current authentication information from the security context.
     */
    public void logout() {
        SecurityContext.clear();
    }
}
