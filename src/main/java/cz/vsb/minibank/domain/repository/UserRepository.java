package cz.vsb.minibank.domain.repository;

import cz.vsb.minibank.domain.User;

import java.util.Optional;

public interface UserRepository {
    Optional<User> byId(int id);

    Optional<User> findByUsername(String username);

    void save(User user);

    int nextId();
}
