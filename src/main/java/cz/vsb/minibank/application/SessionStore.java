package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.User;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionStore {

    private final Map<String, User> sessions = new ConcurrentHashMap<>();

    public String createSession(User user) {
        String id = UUID.randomUUID().toString();
        sessions.put(id, user);
        return id;
    }

    public Optional<User> findUser(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void remove(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }
}
