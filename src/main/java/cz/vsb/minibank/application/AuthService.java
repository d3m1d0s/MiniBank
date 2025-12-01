package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.repository.UserRepository;
import cz.vsb.minibank.domain.exceptions.AuthorizationFailedException;

import java.util.Arrays;
import java.util.Objects;

public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public AuthService(UserRepository users, PasswordEncoder encoder) {
        this.users = Objects.requireNonNull(users, "users");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
    }

    public User login(String username, char[] password) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");

        try {
            User user = users.findByUsername(username)
                    .orElseThrow(() -> new AuthorizationFailedException("Invalid username or password"));

            boolean ok = encoder.matches(password, user.passwordSalt(), user.passwordHash());
            if (!ok) {
                throw new AuthorizationFailedException("Invalid username or password");
            }

            SecurityContext.setCurrentUser(user);
            return user;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public void logout() {
        SecurityContext.clear();
    }
}
