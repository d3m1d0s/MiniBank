package cz.vsb.minibank.uow;

import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.repository.UserRepository;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlUserRepository against a real PostgreSQL: the whole of what a request in SQL mode is
 * authenticated by.
 *
 * Nothing exercised this class. Every other test that has a login in it substitutes an
 * in-memory UserRepository - HttpErrorContractTest, SessionStoreTest and AuthServiceTest each
 * bring their own - and the two SQL integration classes only ever reached the users table
 * through the CASCADE on their truncate. So the implementation the REST API runs on in SQL mode
 * was the one nothing had ever read a row back from, while SessionStore.resolve calls byId on
 * every authenticated request and AuthService reaches findByUsername on every login. A column pair
 * exchanged or a role that comes back as something else is not a reporting defect here; it is
 * who the caller is and what they may do.
 *
 * That is also why the credential assertions are on the bytes rather than on presence. A hash
 * and a salt swapped between the INSERT and the SELECT leave both columns populated and every
 * round trip green under a non-null check, and no password in the database verifiable.
 *
 * Like {@link MinibankSqlUowTests} this needs a live database and reports itself as skipped
 * without one, and it truncates before each case - so it asks {@link TestDatabase} first
 * whether it has been pointed at a database it is allowed to truncate.
 *
 * It sits in that helper's package rather than beside the class it tests because the helper and
 * its guard are package-private, and a second copy of a guard that decides whether a TRUNCATE
 * runs is the last thing this suite needs.
 */
public class SqlUserRepositoryTest {

    /**
     * How many lookups the unbound case makes. Two connections per iteration, and the total is
     * kept well under any plausible max_connections on purpose: a leak must fail this test
     * rather than exhaust the server and take every case after it down with it.
     */
    private static final int LOOKUPS_WITHOUT_A_UOW = 40;

    /**
     * Backends the count may sit above its starting point and still be called clean. Not zero
     * because the counting connection is itself a session, and the previous one may not have
     * finished leaving; wide enough for that and nothing like the eighty a leak would show.
     */
    private static final int BACKEND_SLACK = 2;

    private static final int SETTLE_TIMEOUT_SECONDS = 5;
    private static final long SETTLE_POLL_MILLIS = 50;

    private String jdbcUrl;
    private String dbUser;
    private String dbPass;

    private Bootstrap infra;
    private UserRepository users;

    /** Probed once for the class; the assumption itself is per test so the skips are reported. */
    private static boolean databaseReachable;

    @BeforeAll
    static void probeTestDatabase() {
        TestDatabase.requireSeparateFromApplicationDatabase();
        databaseReachable = TestDatabase.isReachable();
    }

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(databaseReachable, TestDatabase::unreachableMessage);

        jdbcUrl = TestDatabase.url();
        dbUser = TestDatabase.user();
        dbPass = TestDatabase.password();

        // users is named rather than left to the CASCADE the neighbouring classes lean on. It is
        // the table under test, so its sequence has to restart with the others, and a reader
        // should not have to trace a foreign key to see that this class empties it.
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             Statement st = conn.createStatement()) {
            st.execute("""
                    TRUNCATE TABLE users, fraud_alerts, transfers, beneficiaries, accounts, customers
                    RESTART IDENTITY CASCADE
                    """);
        }

        infra = new Bootstrap(jdbcUrl, dbUser, dbPass);
        users = infra.users;
    }

    // -------------------------------------------------------------------------
    // 1) A row written through the repository comes back as what was written
    // -------------------------------------------------------------------------

    /**
     * Both read paths, against the same row, asserted field by field.
     *
     * Read in a unit of work of its own rather than in the one that wrote: inside the writing
     * transaction byId is answered from the identity map with the very instance that was saved,
     * which proves nothing about any column.
     */
    @Test
    void aSavedUserComesBackByIdAndByUsernameWithEveryFieldIntact() {
        SeededUser alice = seedCustomerUser("alice");

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            User byId = users.byId(alice.id())
                    .orElseThrow(() -> new AssertionError("The saved user must be loadable by id"));
            assertMatches(alice, byId, "loaded by id");
            assertTrue(byId.isCustomer(),
                    "a CUSTOMER row carrying a customer id must answer as a customer, which is"
                            + " what every ownership check on the money paths asks it");
            scope.uow().commit();
        }

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            User byUsername = users.findByUsername("alice")
                    .orElseThrow(() -> new AssertionError("The saved user must be loadable by name"));
            assertMatches(alice, byUsername, "loaded by username");
            scope.uow().commit();
        }
    }

    /**
     * Absent is an empty answer on both paths, not a failure.
     *
     * SessionStore depends on exactly this: an empty byId is how it learns that the user behind
     * a live session has been deleted, and it closes the session rather than propagating. An
     * unknown username is the ordinary case on the login path, once per typo.
     */
    @Test
    void aUserNobodyHasIsAnEmptyAnswerRatherThanAFailure() {
        SeededUser alice = seedCustomerUser("alice");

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            assertTrue(users.byId(alice.id() + 1_000).isEmpty(), "no such id");
            assertTrue(users.findByUsername("alice.novakova").isEmpty(), "no such username");
            assertTrue(users.findByUsername("ALICE").isEmpty(),
                    "and the username is matched as stored, not folded");
            scope.uow().commit();
        }
    }

    // -------------------------------------------------------------------------
    // 2) The nullable column
    // -------------------------------------------------------------------------

    /**
     * A user with no customer behind it round trips as null, in the row and in the aggregate.
     *
     * The analyst login is the one this describes, and the mapper reads customer_id with
     * getInt, which answers 0 for a NULL. Only the wasNull check afterwards tells the two
     * apart. Without it the analyst comes back linked to customer 0 and isCustomer starts
     * turning on whether the role happens to be CUSTOMER, so the field that decides whose
     * accounts a caller may see would be a value nobody wrote.
     */
    @Test
    void aUserWithNoCustomerComesBackWithNoneRatherThanWithCustomerZero() throws SQLException {
        SeededUser analyst = seedStaffUser("fraud", UserRole.FRAUD_ANALYST);

        assertNull(rawCustomerIdOf(analyst.id()),
                "the upsert must write SQL NULL for an absent customer, not a zero");

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            User loaded = users.byId(analyst.id())
                    .orElseThrow(() -> new AssertionError("The analyst must be loadable"));
            assertNull(loaded.customerId(), "and the load must bring the absence back as absence");
            assertFalse(loaded.isCustomer(), "an analyst owns no accounts");
            assertEquals(UserRole.FRAUD_ANALYST, loaded.role());
            scope.uow().commit();
        }
    }

    // -------------------------------------------------------------------------
    // 3) The role, which decides what its holder may do
    // -------------------------------------------------------------------------

    @Test
    void everyRoleSurvivesTheStoreAndComesBackAsItself() {
        for (UserRole role : UserRole.values()) {
            SeededUser staff = seedStaffUser("user-" + role.name(), role);

            try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
                User loaded = users.byId(staff.id())
                        .orElseThrow(() -> new AssertionError("Seeded " + role + " must be loadable"));
                assertEquals(role, loaded.role(),
                        "the role is stored as its own name and must come back as itself; every"
                                + " authorization decision in the product is this field");
                scope.uow().commit();
            }
        }
    }

    /**
     * A role string nothing can read is refused by name, on both paths.
     *
     * The comment in mapRow records what this used to be: a NullPointerException on an absent
     * value and a bare IllegalArgumentException on an unknown one, both of which the advice
     * answers as an unexplained 500 on whatever request happened to be revalidating a session.
     * The column has no CHECK, deliberately - the same argument the transfers status column is
     * left uncoloured by - so a row typed into psql is the writer this stands against, and the
     * refusal has to name the row and the value or a corrupt store cannot be found.
     */
    @Test
    void aRoleNobodyCanReadIsRefusedByNameWhenTheRowIsLoaded() throws SQLException {
        int userId = insertRawUser("intruder", "ROOT");

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            DataIntegrityException refused = assertThrows(DataIntegrityException.class,
                    () -> users.byId(userId));
            assertTrue(refused.getMessage().contains("ROOT"),
                    "the refusal must name the value: " + refused.getMessage());
            assertTrue(refused.getMessage().contains("user " + userId),
                    "and the row it came from, as a user rather than as some other kind: "
                            + refused.getMessage());
        }

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            assertThrows(DataIntegrityException.class, () -> users.findByUsername("intruder"),
                    "the login path reads the same column and must refuse it the same way");
        }
    }

    /**
     * A role stored in lower case is still the role it names.
     *
     * Nothing in this codebase writes one - the upsert writes UserRole.name() - so this is about
     * the row somebody typed by hand, which is the same writer the case above stands against.
     * The mapper folds the string before parsing it, and that is asserted rather than left
     * implicit because dropping the fold would read as a tidy-up and would turn such a row into
     * a 500 on every request its holder makes.
     */
    @Test
    void aRoleStoredInLowerCaseIsStillTheRoleItNames() throws SQLException {
        int userId = insertRawUser("hand.written", "customer");

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            User loaded = users.byId(userId)
                    .orElseThrow(() -> new AssertionError("The hand written row must be loadable"));
            assertEquals(UserRole.CUSTOMER, loaded.role());
            scope.uow().commit();
        }
    }

    // -------------------------------------------------------------------------
    // 4) The identity map
    // -------------------------------------------------------------------------

    /**
     * One transaction, one instance per user, and a new transaction reads the row again.
     *
     * The second half is the login sequence: findByUsername puts what it read into the map, so
     * the ownership checks that follow it in the same transaction are answered from it rather
     * than from a second SELECT.
     *
     * What is deliberately not asserted here is the reverse order. findByUsername does not
     * consult the map before its SELECT the way SqlAccountRepository.byIban does, so a byId
     * followed by a findByUsername for the same row yields a second instance. That is harmless
     * as this aggregate stands - User is immutable and carries no optimistic-lock token, so two
     * instances of one row cannot disagree - and it is stated so the silence is not mistaken
     * for coverage.
     */
    @Test
    void oneUnitOfWorkAnswersWithOneInstanceForOneUser() {
        SeededUser alice = seedCustomerUser("alice");

        User fromFirstTransaction;
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            fromFirstTransaction = users.byId(alice.id()).orElseThrow();
            assertSame(fromFirstTransaction, users.byId(alice.id()).orElseThrow(),
                    "within one unit of work byId must be answered from the identity map");
            scope.uow().commit();
        }

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            User signedIn = users.findByUsername("alice").orElseThrow();
            assertSame(signedIn, users.byId(alice.id()).orElseThrow(),
                    "the login lookup must leave the user in the identity map, or every check"
                            + " that follows it re-reads a row this transaction already holds");
            assertNotSame(fromFirstTransaction, signedIn,
                    "and the map belongs to one transaction: the next one reads the row as it is"
                            + " now rather than reusing what an earlier one saw");
            scope.uow().commit();
        }
    }

    // -------------------------------------------------------------------------
    // 5) The connection-per-call path, which is the one authentication takes
    // -------------------------------------------------------------------------

    /**
     * Reads with nothing bound answer from a connection of their own, and give it back.
     *
     * This is not a convenience for tools: SessionStore.resolve revalidates outside any unit of
     * work on purpose - so that the check cannot be answered from the very snapshot it exists to
     * verify - which makes this path the one every authenticated request takes. There is no pool
     * in this project, so each of those lookups opens a PostgreSQL session and a lookup that
     * failed to close one would hold a backend slot for as long as the process ran, and reach
     * max_connections at a rate of one per request.
     *
     * Asserted against pg_stat_activity rather than against the repository, because the claim is
     * about what the server is left holding.
     */
    @Test
    void aLookupWithNoUnitOfWorkBoundAnswersAndGivesItsConnectionBack() throws Exception {
        SeededUser alice = seedCustomerUser("alice");

        assertNull(UowContext.current(),
                "this case is the unbound path; a unit of work left bound would answer it");

        int before = backendsOnTestDatabase();

        for (int i = 0; i < LOOKUPS_WITHOUT_A_UOW; i++) {
            assertMatches(alice, users.byId(alice.id()).orElseThrow(), "byId with nothing bound");
            assertMatches(alice, users.findByUsername("alice").orElseThrow(),
                    "findByUsername with nothing bound");
        }

        int after = backendsAfterSettlingTo(before + BACKEND_SLACK);
        assertTrue(after <= before + BACKEND_SLACK,
                (2 * LOOKUPS_WITHOUT_A_UOW) + " unbound lookups each opened a connection of their"
                        + " own and each must have closed it, but the test database went from "
                        + before + " backends to " + after);
    }

    /**
     * Writes have no such path, and are refused rather than committing on their own.
     *
     * Worth pinning next to the case above: the two halves of this repository disagree on
     * purpose. A read may open a connection because a read has no transaction to belong to; a
     * write must join one, because the row it writes is rarely alone - DemoUsersInitializer
     * writes both demo logins in a single unit of work, and a save that ran on a connection of
     * its own would commit one of them whatever became of the other.
     */
    @Test
    void aWriteWithNoUnitOfWorkIsRefusedRatherThanRunningOnAConnectionOfItsOwn() throws SQLException {
        assertNull(UowContext.current(), "nothing may be bound for this case");

        User stray = new User(1, "stray", hashFor("stray"), saltFor("stray"), UserRole.CUSTOMER, null);

        assertThrows(IllegalStateException.class, () -> users.save(stray),
                "a user save outside a unit of work must be refused");
        assertThrows(IllegalStateException.class, users::nextId,
                "and so must an id: nextval is not transactional and would come back happily, but"
                        + " the save that is the only use for it has just been refused");

        assertEquals(0, countUsers(), "nothing may have reached the table");
    }

    // -------------------------------------------------------------------------
    // 6) The upsert
    // -------------------------------------------------------------------------

    /**
     * Saving a user that already exists rewrites the row rather than adding one.
     *
     * Every column the DO UPDATE arm claims is changed at once, because a column missing from
     * that arm fails silently in the direction that matters: a password change that reports
     * success and leaves the old credentials working is not a defect anybody sees until it is
     * used. The username is changed with them so the row is found by its id, which is what the
     * conflict target is, rather than by the name that happened to be on it.
     */
    @Test
    void savingAUserAgainRewritesTheRowRatherThanAddingASecond() throws SQLException {
        SeededUser alice = seedCustomerUser("alice");
        assertEquals(1, countUsers(), "the fixture is one row");

        byte[] newHash = hashFor("alice.novakova");
        byte[] newSalt = saltFor("alice.novakova");

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            users.save(new User(alice.id(), "alice.novakova", newHash, newSalt,
                    UserRole.OPERATIONS, null));
            scope.uow().commit();
        }

        assertEquals(1, countUsers(),
                "the second save must have found the row by id, not inserted beside it");

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            User reloaded = users.byId(alice.id())
                    .orElseThrow(() -> new AssertionError("The rewritten row must still be there"));

            assertEquals("alice.novakova", reloaded.username());
            assertEquals(UserRole.OPERATIONS, reloaded.role(),
                    "a role taken away must be taken away in the row as well");
            assertNull(reloaded.customerId(),
                    "and so must a customer link, or the row keeps an ownership it was stripped of");
            assertArrayEquals(newHash, reloaded.passwordHash(),
                    "the new hash must be what the row holds; leaving the old one there is a"
                            + " password change that changed nothing and said it had");
            assertArrayEquals(newSalt, reloaded.passwordSalt(),
                    "and the new salt with it: the pair only verifies together");

            assertTrue(users.findByUsername("alice").isEmpty(),
                    "the old username must no longer find anybody");
            scope.uow().commit();
        }
    }

    // ------------------------------------------------------------------
    // fixture and plumbing
    // ------------------------------------------------------------------

    /** A user written and committed, beside the values it was written with. */
    private record SeededUser(int id, String username, byte[] hash, byte[] salt,
                              UserRole role, Integer customerId) { }

    /** A customer login: a real customers row and a user pointing at it, as the demo has it. */
    private SeededUser seedCustomerUser(String username) {
        return seed(username, UserRole.CUSTOMER, true);
    }

    /** A login with no customer behind it, which is what every non-customer role is. */
    private SeededUser seedStaffUser(String username, UserRole role) {
        return seed(username, role, false);
    }

    /**
     * Writes the customer first when there is one, because users.customer_id is a foreign key
     * and the mutations run at commit in the order they were registered.
     */
    private SeededUser seed(String username, UserRole role, boolean withCustomer) {
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            Integer customerId = null;
            if (withCustomer) {
                int id = infra.customers.nextId();
                infra.customers.save(new Customer(id, "Owner of " + username,
                        username + "@example.com", new Address("Hlavni 1", "Ostrava")));
                customerId = id;
            }

            int userId = users.nextId();
            byte[] hash = hashFor(username);
            byte[] salt = saltFor(username);
            users.save(new User(userId, username, hash, salt, role, customerId));

            scope.uow().commit();
            return new SeededUser(userId, username, hash, salt, role, customerId);
        }
    }

    private void assertMatches(SeededUser seeded, User loaded, String where) {
        assertEquals(seeded.id(), loaded.id(), where);
        assertEquals(seeded.username(), loaded.username(), where);
        assertEquals(seeded.role(), loaded.role(), where);
        assertEquals(seeded.customerId(), loaded.customerId(), where);
        assertArrayEquals(seeded.hash(), loaded.passwordHash(),
                "the hash must come back byte for byte and out of its own column: " + where);
        assertArrayEquals(seeded.salt(), loaded.passwordSalt(),
                "and the salt out of its own: " + where);
    }

    /**
     * The two credential values, shaped so they cannot be mistaken for one another.
     *
     * Different lengths - the 32 and 16 bytes Pbkdf2PasswordEncoder really produces - different
     * contents, and both carrying bytes above 0x7f. So a hash and a salt exchanged in the
     * INSERT or in the SELECT list is a failed assertion rather than two byte arrays that
     * happen to compare equal, and a value that went through a String on the way is one too.
     */
    private static byte[] hashFor(String username) {
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (0xA0 + i + username.length());
        }
        return hash;
    }

    private static byte[] saltFor(String username) {
        byte[] salt = new byte[16];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) (0xF0 - i - username.length());
        }
        return salt;
    }

    /**
     * A row written behind the repository's back, for the values it would never write itself.
     * Returns the id the sequence gave it, so a loader can be pointed at it.
     */
    private int insertRawUser(String username, String role) throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO users (id, username, role, customer_id,
                                        password_hash, password_salt)
                     VALUES (nextval('users_id_seq'), ?, ?, NULL, ?, ?)
                     RETURNING id
                     """)) {
            ps.setString(1, username);
            ps.setString(2, role);
            ps.setBytes(3, hashFor(username));
            ps.setBytes(4, saltFor(username));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** The column as the database holds it, so an absent customer can be told from a zero. */
    private Integer rawCustomerIdOf(int userId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT customer_id FROM users WHERE id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no users row with id " + userId);
                int customerId = rs.getInt(1);
                return rs.wasNull() ? null : customerId;
            }
        }
    }

    private int countUsers() throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Sessions on the test database other than the one asking. */
    private int backendsOnTestDatabase() throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT COUNT(*) FROM pg_stat_activity
                      WHERE datname = current_database() AND pid <> pg_backend_pid()
                     """)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * The same count, once it has had a chance to fall to what the caller expects.
     *
     * Polled rather than read once because closing a JDBC connection is not synchronous at the
     * server: close sends the terminate message and returns, and the backend leaves this view
     * shortly afterwards. A single reading taken the instant a loop of lookups ends can still
     * count a session on its way out, and failing a leak test for that would be a false alarm.
     * A count held up by a real leak never falls, so the waiting costs nothing where it matters.
     */
    private int backendsAfterSettlingTo(int limit) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_TIMEOUT_SECONDS);
        int count = backendsOnTestDatabase();
        while (count > limit && System.nanoTime() - deadline < 0) {
            Thread.sleep(SETTLE_POLL_MILLIS);
            count = backendsOnTestDatabase();
        }
        return count;
    }
}
