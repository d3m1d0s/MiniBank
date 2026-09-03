package cz.vsb.minibank.infrastructure.memory;

import cz.vsb.minibank.application.auth.PasswordEncoder;
import cz.vsb.minibank.application.auth.Pbkdf2PasswordEncoder;
import cz.vsb.minibank.domain.customer.User;
import cz.vsb.minibank.domain.customer.UserRole;
import cz.vsb.minibank.domain.exceptions.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What this store owes a caller about usernames, which is what SqlUserRepository owes them.
 *
 * The two are one contract with two implementations: Bootstrap hands this one out as the
 * UserRepository for the whole of JSON mode, so a divergence here is the same application
 * authenticating differently depending on which backend it was started with. On SQL,
 * users.username is NOT NULL UNIQUE and the upsert conflicts on the id, so a second holder of a
 * login is refused and a rename leaves the previous name finding nobody - the case
 * SqlUserRepositoryTest.savingAUserAgainRewritesTheRowRatherThanAddingASecond pins there.
 *
 * Both were legal here. A duplicate save silently repointed the username index while both users
 * stayed readable by id, and a rename left the old name mapped to the old snapshot, which is a
 * retired login still authenticating with retired credentials. Nothing in the application reaches
 * either - the seeders read for the login before writing it - so these are the cases that keep it
 * that way rather than reports of a live failure.
 *
 * No database and no Spring: the store is a plain map behind an interface, and every case here is
 * about which user comes back.
 */
class InMemoryUserRepositoryTest {

    private static final int CUSTOMER_ID = 42;
    private static final int ALICE = 1;
    private static final int MALLORY = 2;

    /** Enough threads to collide reliably, few enough to stay fast. */
    private static final int THREADS = 8;

    /** No thread should ever wait this long; a timeout means something is wedged. */
    private static final int TIMEOUT_SECONDS = 60;

    private static final PasswordEncoder ENCODER = new Pbkdf2PasswordEncoder();
    private static final byte[] SALT = ENCODER.generateSalt();
    private static final byte[] SECRET = ENCODER.hash("secret123".toCharArray(), SALT);

    /** A second credential snapshot, so a save that should replace one can be told from one that did not. */
    private static final byte[] ROTATED_SECRET = ENCODER.hash("rotated456".toCharArray(), SALT);

    private InMemoryUserRepository users;

    @BeforeEach
    void setUp() {
        users = new InMemoryUserRepository();
    }

    /**
     * The duplicate. SQL answers this with a ConflictException out of SqlWriteFailure, and it is
     * the incumbent that keeps the login, because the row that is already there is the one the
     * constraint protects.
     */
    @Test
    void aSecondUserUnderAnExistingUsernameIsRefused() {
        users.save(customer(ALICE, "alice", SECRET));

        ConflictException refused = assertThrows(ConflictException.class,
                () -> users.save(customer(MALLORY, "alice", ROTATED_SECRET)),
                "two users sharing one login is what the UNIQUE constraint refuses on the other backend");

        assertFalse(refused.getMessage().contains("alice"),
                "ConsoleMenu prints this message to its operator, so it names the user and not the"
                        + " value that collided");
        assertEquals(ALICE, users.findByUsername("alice").orElseThrow().id(),
                "the login must still belong to the user that held it");
        assertTrue(users.byId(MALLORY).isEmpty(),
                "and a refused save must leave nothing readable by id either");
    }

    /**
     * The rename, and the one with teeth: the old name kept working, with the old hash and the old
     * role behind it. AuthService looks a login up by name and matches the credentials it finds
     * there, so a name left in the index is a login that was taken away and still opens a session.
     */
    @Test
    void aUserSavedUnderANewNameLeavesTheOldNameUnusable() {
        users.save(customer(ALICE, "alice", SECRET));
        users.save(customer(ALICE, "alice.novakova", ROTATED_SECRET));

        assertTrue(users.findByUsername("alice").isEmpty(),
                "the old username must find nobody, as it finds nobody after the SQL upsert");

        User renamed = users.findByUsername("alice.novakova").orElseThrow();
        assertEquals(ALICE, renamed.id());
        assertArrayEquals(ROTATED_SECRET, renamed.passwordHash(),
                "and the name that is left carries the credentials of the save that set it");
        assertSame(renamed, users.byId(ALICE).orElseThrow(),
                "byId and findByUsername must never answer with two different users for one id");
    }

    /**
     * The other half of the refusal, so it does not overshoot: a user is refused a name that
     * belongs to somebody else, never its own. Saving over a user under its own login is how a
     * password is changed, and refusing it would be refusing that.
     */
    @Test
    void savingAUserAgainUnderItsOwnNameChangesItRatherThanColliding() {
        users.save(customer(ALICE, "alice", SECRET));
        users.save(customer(ALICE, "alice", ROTATED_SECRET));

        User stored = users.findByUsername("alice").orElseThrow();
        assertEquals(ALICE, stored.id());
        assertArrayEquals(ROTATED_SECRET, stored.passwordHash(),
                "an update that left the old hash in place is a password change that changed nothing");
        assertSame(stored, users.byId(ALICE).orElseThrow());
    }

    /**
     * The other limit on the refusal, and the one a later reader is likeliest to "fix": the names
     * are compared with String.equals. users.username is a plain VARCHAR, PostgreSQL compares
     * those case-sensitively, so folding case here would refuse a pair of logins the SQL backend
     * accepts - the same divergence in the opposite direction.
     */
    @Test
    void twoLoginsDifferingOnlyInCaseAreTwoLogins() {
        users.save(customer(ALICE, "alice", SECRET));
        users.save(customer(MALLORY, "Alice", ROTATED_SECRET));

        assertEquals(ALICE, users.findByUsername("alice").orElseThrow().id());
        assertEquals(MALLORY, users.findByUsername("Alice").orElseThrow().id(),
                "'Alice' is a login of its own on both backends, or on neither");
    }

    /**
     * The ordinary path, spelled the way DemoUsersInitializer.ensureDemoUsers spells it, because
     * that is the only caller in the application and every assertion above would hold just as well
     * in a store that refused everything.
     */
    @Test
    void theLookThenWritePathTheSeedersUseFindsBothLoginsAfterwards() {
        assertTrue(users.findByUsername("alice").isEmpty(), "a fresh store knows nobody");
        int aliceId = users.nextId();
        users.save(new User(aliceId, "alice", SECRET, SALT, UserRole.CUSTOMER, CUSTOMER_ID));

        assertTrue(users.findByUsername("fraud").isEmpty());
        int analystId = users.nextId();
        users.save(new User(analystId, "fraud", ROTATED_SECRET, SALT, UserRole.FRAUD_ANALYST, null));

        assertNotEquals(aliceId, analystId, "two seeded logins must not share an id");

        User alice = users.findByUsername("alice").orElseThrow();
        assertEquals(UserRole.CUSTOMER, alice.role());
        assertEquals(CUSTOMER_ID, alice.customerId());
        assertSame(alice, users.byId(aliceId).orElseThrow());

        User analyst = users.findByUsername("fraud").orElseThrow();
        assertEquals(UserRole.FRAUD_ANALYST, analyst.role());
        assertNull(analyst.customerId(), "an analyst login has no customer behind it");
        assertSame(analyst, users.byId(analystId).orElseThrow());
    }

    /**
     * The refusal has to survive a race, or it is only a comment: the seeders read for a login and
     * then write it, so two threads can both find it absent, which is the shape SqlUserRepository
     * documents at its own upsert. The store is the last thing that can hold the line.
     */
    @Test
    void concurrentSavesUnderOneUsernameLetExactlyOneThrough() throws Exception {
        List<ConflictException> refusals = saveConcurrently(thread -> customer(thread + 1, "alice", SECRET));

        assertEquals(THREADS - 1, refusals.size(),
                "one save may take the login and every other must be refused it");

        User holder = users.findByUsername("alice").orElseThrow();
        assertSame(holder, users.byId(holder.id()).orElseThrow());

        for (int thread = 0; thread < THREADS; thread++) {
            int id = thread + 1;
            if (id == holder.id()) {
                continue;
            }
            assertTrue(users.byId(id).isEmpty(),
                    "user " + id + " was refused the login, so it must not be in the store at all");
        }
    }

    /**
     * The same race over one id instead of one name. Whichever save lands last is the user that id
     * holds, and it must be the only one findable by name: every name the others carried was taken
     * off that id and must find nobody.
     */
    @Test
    void concurrentSavesOfOneIdLeaveOneUsernameThatFindsIt() throws Exception {
        List<ConflictException> refusals = saveConcurrently(thread -> customer(ALICE, "alice-" + thread, SECRET));

        assertTrue(refusals.isEmpty(), "each save here carries a name of its own, so none of them clash");

        User stored = users.byId(ALICE).orElseThrow();

        for (int thread = 0; thread < THREADS; thread++) {
            String name = "alice-" + thread;
            Optional<User> found = users.findByUsername(name);
            if (name.equals(stored.username())) {
                assertSame(stored, found.orElseThrow(),
                        "the surviving name must find the user that id holds, not an earlier snapshot");
            } else {
                assertTrue(found.isEmpty(),
                        "'" + name + "' was overwritten and must find nobody, or a login the store"
                                + " has replaced still authenticates with the credentials it had");
            }
        }
    }

    // ------------------------------------------------------------------
    // fixture and plumbing
    // ------------------------------------------------------------------

    private static User customer(int id, String username, byte[] hash) {
        return new User(id, username, hash, SALT, UserRole.CUSTOMER, CUSTOMER_ID);
    }

    /**
     * Runs one save on each of THREADS threads, released together from a barrier so they enter the
     * store at once, and returns the refusals. A save that goes through returns nothing to collect;
     * anything other than a ConflictException comes back out of Future.get and fails the test with
     * itself as the cause.
     */
    private List<ConflictException> saveConcurrently(IntFunction<User> user) throws Exception {
        CyclicBarrier start = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<ConflictException>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < THREADS; thread++) {
                int index = thread;
                futures.add(pool.submit(() -> {
                    start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    try {
                        users.save(user.apply(index));
                        return null;
                    } catch (ConflictException refused) {
                        return refused;
                    }
                }));
            }

            List<ConflictException> refusals = new ArrayList<>();
            for (Future<ConflictException> f : futures) {
                ConflictException refused = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (refused != null) {
                    refusals.add(refused);
                }
            }
            return refusals;
        } finally {
            pool.shutdownNow();
        }
    }
}
