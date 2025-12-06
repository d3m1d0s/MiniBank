package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {

    private static User dummyUser() {
        return new User(
                1,
                "alice",
                new byte[]{1},
                new byte[]{2},
                UserRole.CUSTOMER,
                42
        );
    }

    @Test
    void createAndFindSession() {
        SessionStore store = new SessionStore();
        User user = dummyUser();

        String sessionId = store.createSession(user);
        assertNotNull(sessionId);
        assertFalse(sessionId.isBlank());

        Optional<User> found = store.findUser(sessionId);
        assertTrue(found.isPresent());
        assertEquals("alice", found.get().username());
        assertEquals(42, found.get().customerId());
    }

    @Test
    void removeSessionMakesItUnavailable() {
        SessionStore store = new SessionStore();
        User user = dummyUser();

        String sessionId = store.createSession(user);
        store.remove(sessionId);

        assertTrue(store.findUser(sessionId).isEmpty());
    }

    @Test
    void findUserWithNullOrUnknownIdReturnsEmpty() {
        SessionStore store = new SessionStore();

        assertTrue(store.findUser(null).isEmpty());
        assertTrue(store.findUser("no-such-id").isEmpty());
    }
}
