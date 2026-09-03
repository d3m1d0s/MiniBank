package cz.vsb.minibank.domain.repository;

import cz.vsb.minibank.domain.customer.User;

import java.util.Optional;

/**
 * Repository abstraction for application users.
 */
public interface UserRepository {

    /**
     * Finds a user by identifier.
     */
    Optional<User> byId(int id);

    /**
     * Finds a user by unique username.
     */
    Optional<User> findByUsername(String username);

    /**
     * Inserts or updates a user.
     */
    void save(User user);

    /**
     * Returns the next technical identifier for a new user.
     */
    int nextId();
}
