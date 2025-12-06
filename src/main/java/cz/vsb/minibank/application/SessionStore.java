package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.User;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in memory session store keyed by a generated session identifier.
 */
public class SessionStore {

    private final Map<String, User> sessions = new ConcurrentHashMap<>();

    /**
     * Creates a new session for the given user and returns the session ID.
     */
    public String createSession(User user) {
        String id = UUID.randomUUID().toString();
        sessions.put(id, user);
        return id;
    }

    /**
     * Finds the user associated with the given session ID.
     *
     * @param sessionId session identifier
     * @return optional user if the session exists
     */
    public Optional<User> findUser(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * Removes the session with the given ID if it exists.
     */
    public void remove(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }
}
