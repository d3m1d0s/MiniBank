package cz.vsb.minibank.infrastructure.memory;

import cz.vsb.minibank.domain.customer.User;
import cz.vsb.minibank.domain.exceptions.ConflictException;
import cz.vsb.minibank.domain.repository.UserRepository;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe in-memory implementation of UserRepository.
 * Intended for demo and JSON-based modes where users are not persisted.
 *
 * A shim, but not a private one: Bootstrap hands this out as the UserRepository for the whole of
 * JSON mode, so anything written against it is written against the contract SqlUserRepository
 * answers, and the two used to differ. There, users.username is NOT NULL UNIQUE and the upsert
 * conflicts on the id; here two maps were written with no check between them, which made a second
 * user under an existing login silently legal and left a renamed user still answering to the name
 * it used to have, with the credentials it used to have.
 *
 * Holding the id map alone is what closes both, and the concurrent third with them: there is no
 * second structure to fall out of step, so byId and findByUsername cannot disagree about which
 * user an id is. A username lookup scans instead, which is the price, and against the handful of
 * rows the seeders write it is not a price anybody can measure. The comparison is String.equals
 * because users.username is a plain VARCHAR and PostgreSQL compares those case-sensitively:
 * "Alice" and "alice" are two logins on both backends, or on neither.
 *
 * No caller reaches the refusal today - both demo seeders read for the login before writing it -
 * so this closes a hole in the contract rather than changing what the application does.
 */
public class InMemoryUserRepository implements UserRepository {

    private final Map<Integer, User> byId = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(1);

    @Override
    public Optional<User> byId(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return byId.values().stream()
                .filter(u -> u.username().equals(username))
                .findFirst();
    }

    /**
     * Inserts or updates a user, refusing a username another user already holds.
     *
     * ConflictException, and with the wording SqlWriteFailure gives the same collision, so a
     * caller is answered alike whichever backend is under it, down to the message: it names the
     * aggregate and its id and stops there, because ConsoleMenu prints the message of any
     * DomainException straight to its operator and the colliding value is not for them.
     *
     * A rename is allowed, and retires the old name, because that is what the SQL upsert does -
     * its conflict target is the id, so a save carrying a new username rewrites the row rather
     * than adding one beside it, and nobody holds the previous name afterwards.
     *
     * Synchronized because the check and the store must not interleave with another save. That is
     * all the atomicity this needs: with one map to write, a reader can never catch the store
     * half-updated.
     */
    @Override
    public synchronized void save(User user) {
        Objects.requireNonNull(user, "user");

        User holder = findByUsername(user.username()).orElse(null);
        if (holder != null && holder.id() != user.id()) {
            throw new ConflictException("Cannot store user " + user.id()
                    + ": another row already holds a value this one must not repeat");
        }

        byId.put(user.id(), user);
    }

    @Override
    public int nextId() {
        return seq.getAndIncrement();
    }
}
