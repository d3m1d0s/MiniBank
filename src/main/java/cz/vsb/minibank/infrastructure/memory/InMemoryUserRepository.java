package cz.vsb.minibank.infrastructure.memory;

import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.repository.UserRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe in-memory implementation of UserRepository.
 * Intended for demo and JSON-based modes where users are not persisted.
 */
public class InMemoryUserRepository implements UserRepository {

    private final Map<Integer, User> byId = new ConcurrentHashMap<>();
    private final Map<String, User> byUsername = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(1);

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
        return seq.getAndIncrement();
    }
}
