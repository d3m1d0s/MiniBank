package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.exceptions.TooManySessionsException;
import cz.vsb.minibank.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backlog item A10: a session is an identity and two timestamps, not a copy of a user.
 *
 * What it replaced was a ConcurrentHashMap from session id to the login-time User object,
 * with no recorded time of capture, no lifetime and no bound. Reproduced against PostgreSQL:
 * the user row was deleted, a fresh login as that user was correctly refused, and the
 * existing session still listed accounts and still moved 500 CZK out of an account. So every
 * case here is written to fail against that arrangement, not merely to describe this one.
 *
 * The clock is advanced, never slept on. Time has to move inside one store instance, because
 * the sessions are what is under test, which is why this needs TestClock rather than the
 * Clock.fixed that A9's tests get away with.
 */
class SessionStoreTest {

    private static final Instant T0 = Instant.parse("2026-01-01T09:00:00Z");
    private static final int USER_ID = 1;

    private MutableUsers users;
    private TestClock clock;
    private SessionStore store;

    @BeforeEach
    void setUp() {
        users = new MutableUsers();
        users.put(user(USER_ID, "alice", UserRole.CUSTOMER, 42));
        clock = new TestClock(T0);
        store = new SessionStore(users, clock);
    }

    private static User user(int id, String username, UserRole role, Integer customerId) {
        return new User(id, username, new byte[]{1}, new byte[]{2}, role, customerId);
    }

    private String signIn() {
        return store.createSession(users.byId(USER_ID).orElseThrow());
    }

    // ------------------------------------------------------------ the ordinary path

    @Test
    void resolvesToTheUserBehindTheSession() {
        String id = signIn();
        assertNotNull(id);
        assertFalse(id.isBlank());

        Optional<User> found = store.resolve(id);
        assertTrue(found.isPresent());
        assertEquals("alice", found.get().username());
        assertEquals(42, found.get().customerId());
        assertEquals(UserRole.CUSTOMER, found.get().role());
    }

    @Test
    void removeAndUnknownAndNullIdsAllResolveToNothing() {
        String id = signIn();
        store.remove(id);

        assertTrue(store.resolve(id).isEmpty());
        assertTrue(store.resolve(null).isEmpty());
        assertTrue(store.resolve("  ").isEmpty());
        assertTrue(store.resolve("no-such-id").isEmpty());
    }

    @Test
    void concurrentSessionsOfOneUserStayIndependent() {
        String first = signIn();
        String second = signIn();

        assertNotEquals(first, second);
        store.remove(first);

        assertTrue(store.resolve(first).isEmpty());
        assertTrue(store.resolve(second).isPresent());
    }

    // ------------------------------------------------------------ identity is re-read

    /** A10's headline case: the deleted user still listed accounts and still moved money. */
    @Test
    void aSessionStopsResolvingWhenItsUserIsDeleted() {
        String id = signIn();
        users.delete(USER_ID);

        assertTrue(store.resolve(id).isEmpty());
        assertEquals(0, store.size(), "the entry must go with the user, not linger");
    }

    /**
     * An id is not an identity. A row deleted and re-created out of band can put a different
     * person on the same id, and out-of-band writes are how this defect was reproduced in the
     * first place. Without the username check the old session would authenticate as whoever
     * now holds the id - a failure mode the login-time snapshot did not have.
     */
    @Test
    void aSessionStopsResolvingWhenItsUserIdIsReusedBySomebodyElse() {
        String id = signIn();
        users.delete(USER_ID);
        users.put(user(USER_ID, "mallory", UserRole.FRAUD_ANALYST, null));

        assertTrue(store.resolve(id).isEmpty());
        assertEquals(0, store.size());
    }

    /** The gate A8 depends on: a demoted analyst kept the role until they chose to sign in again. */
    @Test
    void resolvesTheCurrentRoleAndNotTheOneCapturedAtLogin() {
        String id = signIn();
        users.put(user(USER_ID, "alice", UserRole.FRAUD_ANALYST, null));

        User seen = store.resolve(id).orElseThrow();
        assertEquals(UserRole.FRAUD_ANALYST, seen.role());
        assertNull(seen.customerId());
        assertFalse(seen.isCustomer(), "AuthHelpers.requireCustomerId must refuse on the next request");
    }

    /** The other direction, and the one that stops a session acting for the wrong customer. */
    @Test
    void resolvesTheCurrentCustomerIdAndNotTheOneCapturedAtLogin() {
        String id = signIn();
        users.put(user(USER_ID, "alice", UserRole.CUSTOMER, 99));

        assertEquals(99, store.resolve(id).orElseThrow().customerId());

        users.put(user(USER_ID, "alice", UserRole.CUSTOMER, null));
        assertFalse(store.resolve(id).orElseThrow().isCustomer());
    }

    // ------------------------------------------------------------ expiry

    @Test
    void anIdleSessionExpiresAndIsDroppedFromTheMap() {
        String id = signIn();
        clock.advance(SessionStore.IDLE_TIMEOUT.plusSeconds(1));

        assertTrue(store.resolve(id).isEmpty());
        assertEquals(0, store.size());
    }

    /**
     * The deadline to the nanosecond, and that use moves it. Without the first half a later
     * "simplification" of the predicate to isAfter would flip the boundary and leave every
     * other case here green.
     */
    @Test
    void theIdleDeadlineIsExactAndUsingASessionMovesIt() {
        String used = signIn();
        String untouched = signIn();

        clock.advance(SessionStore.IDLE_TIMEOUT.minusNanos(1));
        assertTrue(store.resolve(used).isPresent(), "one nanosecond short of the deadline is alive");

        clock.advance(Duration.ofNanos(1));
        assertTrue(store.resolve(untouched).isEmpty(), "exactly at the deadline is dead");
        assertTrue(store.resolve(used).isPresent(), "the one that was used has a new deadline");
    }

    /** The reproduced case was a 29 minute old session answering 200. Age alone must not end one. */
    @Test
    void useKeepsASessionAliveWellPastTheIdleTimeout() {
        String id = signIn();
        for (int i = 0; i < 4; i++) {
            clock.advance(SessionStore.IDLE_TIMEOUT.minusMinutes(1));
            assertTrue(store.resolve(id).isPresent());
        }

        assertTrue(Duration.between(T0, clock.instant()).toMinutes() > 50,
                "well past the twenty-nine minutes that used to be the complaint");
    }

    /** ...but not forever, which is what stops a busy client holding an entry for good. */
    @Test
    void theAbsoluteLifetimeEndsASessionThatIsStillInUse() {
        String id = signIn();
        Duration step = Duration.ofMinutes(10);

        while (Duration.between(T0, clock.instant()).compareTo(SessionStore.MAX_LIFETIME.minus(step)) < 0) {
            clock.advance(step);
            assertTrue(store.resolve(id).isPresent(), "the ceiling must not fire early");
        }

        clock.advance(step);
        assertTrue(store.resolve(id).isEmpty());
        assertEquals(0, store.size());
    }

    // ------------------------------------------------------------ the bound

    @Test
    void aLoginSweepsSessionsNobodyCameBackFor() {
        signIn();
        signIn();
        clock.advance(SessionStore.IDLE_TIMEOUT.plusSeconds(1));

        signIn();
        assertEquals(1, store.size(), "the abandoned two are cleared by the next person's login");
    }

    /**
     * The invariant that actually bounds the map, and it holds at every instant with no help
     * from any client: the cap is enforced inside the one synchronized method that inserts.
     */
    @Test
    void oneUserNeverHoldsMoreThanTheCapHoweverOftenTheySignIn() {
        for (int i = 0; i < 20; i++) {
            clock.advance(Duration.ofSeconds(1));
            signIn();
        }

        assertEquals(SessionStore.MAX_SESSIONS_PER_USER, store.size());
    }

    /**
     * Eviction takes the least recently used session, not the oldest opened one. Here the
     * first session is the oldest and also the one being worked in; evicting by creation time
     * would sign that tab out and keep four that nobody has touched since they were opened.
     */
    @Test
    void aFurtherLoginClosesTheUsersLeastRecentlyUsedSession() {
        String workedIn = signIn();
        List<String> others = new ArrayList<>();
        for (int i = 1; i < SessionStore.MAX_SESSIONS_PER_USER; i++) {
            clock.advance(Duration.ofSeconds(1));
            others.add(signIn());
        }

        clock.advance(Duration.ofSeconds(1));
        assertTrue(store.resolve(workedIn).isPresent());

        clock.advance(Duration.ofSeconds(1));
        signIn();

        assertTrue(store.resolve(workedIn).isPresent(), "the session in use must survive");
        assertTrue(store.resolve(others.get(0)).isEmpty(), "the least recently used one goes");
        assertEquals(SessionStore.MAX_SESSIONS_PER_USER, store.size());
    }

    /**
     * A full store refuses the new login rather than signing anybody else out. Both options
     * are a denial of service under a flood; this is the one where nobody is signed out
     * because somebody else signed in.
     */
    @Test
    void aFullStoreRefusesToOpenAnotherSessionAndSignsNobodyOut() {
        List<String> opened = new ArrayList<>();
        for (int i = 0; i < SessionStore.MAX_SESSIONS; i++) {
            int id = i + 100;
            users.put(user(id, "u" + id, UserRole.CUSTOMER, id));
            opened.add(store.createSession(users.byId(id).orElseThrow()));
        }

        assertThrows(TooManySessionsException.class, this::signIn);
        assertTrue(store.resolve(opened.get(0)).isPresent(), "an existing session must not be evicted");
        assertEquals(SessionStore.MAX_SESSIONS, store.size());
    }

    /**
     * A repository the test can empty. UserRepository has no delete and neither in-tree
     * implementation has one; adding one to production code that only a test would call would
     * be the wrong trade, because the row vanishing is something the database does out of
     * band, which is exactly how the defect was reproduced.
     */
    private static final class MutableUsers implements UserRepository {
        private final Map<Integer, User> rows = new HashMap<>();

        void put(User u) { rows.put(u.id(), u); }
        void delete(int id) { rows.remove(id); }

        @Override public Optional<User> byId(int id) { return Optional.ofNullable(rows.get(id)); }
        @Override public Optional<User> findByUsername(String username) {
            return rows.values().stream().filter(u -> u.username().equals(username)).findFirst();
        }
        @Override public void save(User user) { put(user); }
        @Override public int nextId() { return rows.size() + 1; }
    }
}
