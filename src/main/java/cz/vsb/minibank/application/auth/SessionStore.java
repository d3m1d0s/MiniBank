package cz.vsb.minibank.application.auth;

import cz.vsb.minibank.domain.customer.User;
import cz.vsb.minibank.domain.exceptions.TooManySessionsException;
import cz.vsb.minibank.domain.repository.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import cz.vsb.minibank.application.audit.AppLogger;

/**
 * In memory session store: a session id, whose session it is, and when.
 *
 * What it deliberately does not hold is the {@link User}. A session used to be the
 * login-time object itself, so it outlived the row it was copied from: a user deleted from
 * the database went on listing accounts and went on moving money, a role stripped from a
 * user stayed in force until that user chose to sign in again, and the password hash and
 * salt sat in this map for as long as the process lived. Every lookup now re-reads the user
 * through the repository and answers with the row as it is now, so all three of those end
 * on the session's next request rather than at its next login.
 *
 * Sessions are also bounded, in three ways that need no background thread: they are dropped
 * when found expired, swept on the login path, and capped per user. What holds
 * unconditionally, at every instant and whatever any client does, is the per-user cap - it
 * is enforced inside the one synchronized method that can insert. The two timeouts bound how
 * long a session lives, which is a security property; they are not what bounds the map,
 * because nothing sweeps unless somebody logs in or presents an id.
 */
public class SessionStore {

    /**
     * How long a session survives without being used, refreshed on every successful lookup.
     * Neither frontend polls, so this is the bound that actually fires: an abandoned tab
     * stops being able to move money a quarter of an hour after the last click. A session
     * used a minute ago survives however old it is - idleness expires, not age.
     */
    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(15);

    /**
     * How long a session survives at all, however heavily it is used. Not refreshed, so a
     * client that requests every fourteen minutes forever is still signed out after this.
     */
    public static final Duration MAX_LIFETIME = Duration.ofHours(12);

    /**
     * Concurrent sessions one user may hold. A further login closes that user's own least
     * recently used session rather than being refused: signing in again is ordinary, and the
     * eviction can only ever reach sessions of the user who has just proved their password.
     *
     * Least recently used, not oldest opened. Nothing in this product reliably closes a
     * session - the id lives in a module-level variable that a page reload discards - so
     * "oldest" is usually a leftover from a reload and "in use" is usually the newest thing
     * the person is actually looking at. Evicting by creation time would sign out the tab
     * being worked in and keep four abandoned ones.
     */
    public static final int MAX_SESSIONS_PER_USER = 5;

    /**
     * Sessions the store will hold in total. With the per-user cap enforced at every insert,
     * this is not reachable by a login flood from one credential - that tops out at five - so
     * it is a guard for a deployment with a couple of hundred distinct users rather than an
     * anti-abuse measure. Reaching it refuses the new login instead of evicting an existing
     * session: nobody may be signed out because somebody else signed in.
     */
    public static final int MAX_SESSIONS = 1_000;

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final UserRepository users;
    private final Clock clock;

    /**
     * @param clock supplies "now" for both timeouts. Its zone is deliberately left alone:
     *              these are elapsed-time bounds on an Instant, with none of the calendar
     *              boundary that makes the transfer service force its own zone. Both bounds
     *              are wall-clock, so a clock stepped backwards - an NTP correction, a
     *              resumed VM - lengthens them by the size of the step. A forward step
     *              shortens them, which is the safe direction.
     */
    public SessionStore(UserRepository users, Clock clock) {
        this.users = Objects.requireNonNull(users, "users");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Opens a session for a user whose password has just been checked, and returns its id.
     *
     * Synchronized because the sweep, the two caps and the insert are one decision over the
     * whole map, and a ConcurrentHashMap makes each step atomic but not the sequence - two
     * concurrent logins could otherwise both observe one short of the cap and both insert.
     * The lock costs nothing: the caller has just spent a PBKDF2 hash outside it, and that
     * same deliberately expensive hash is what keeps the O(n) sweep off any hot path.
     *
     * @throws TooManySessionsException when the store is full
     */
    public synchronized String createSession(User user) {
        Objects.requireNonNull(user, "user");
        Instant now = clock.instant();

        String lruOfThisUser = null;
        Instant lruSeenAt = null;
        int heldByThisUser = 0;

        // One pass does all three jobs: drop what has expired, count what this user already
        // holds, and remember the one they have gone longest without using, in case room has
        // to be made. This is also what removes sessions nobody ever came back for - the next
        // person's login clears them rather than them waiting for an id that is never
        // presented again.
        Iterator<Map.Entry<String, Entry>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> e = it.next();
            Entry entry = e.getValue();
            if (entry.hasExpired(now)) {
                it.remove();
                continue;
            }
            if (entry.userId == user.id()) {
                heldByThisUser++;
                Instant seen = entry.lastSeenAt;
                if (lruSeenAt == null || seen.isBefore(lruSeenAt)) {
                    lruSeenAt = seen;
                    lruOfThisUser = e.getKey();
                }
            }
        }

        if (heldByThisUser >= MAX_SESSIONS_PER_USER && lruOfThisUser != null) {
            sessions.remove(lruOfThisUser);
            AppLogger.info("auth", "User " + user.id() + " already holds " + MAX_SESSIONS_PER_USER
                    + " sessions; the least recently used one was closed to make room");
        }

        if (sessions.size() >= MAX_SESSIONS) {
            AppLogger.error("auth", "Session store is full at " + MAX_SESSIONS
                    + " sessions; refusing to open another", null);
            throw new TooManySessionsException("Session store is full at " + MAX_SESSIONS);
        }

        String id = UUID.randomUUID().toString();
        sessions.put(id, new Entry(user.id(), user.username(), now));
        return id;
    }

    /**
     * Resolves a session id to the user behind it as that user is now, or to nothing.
     *
     * Nothing means one of four things - no such session, an expired one, one whose user has
     * been deleted, or one whose user id now names somebody else - and the caller cannot tell
     * them apart, because all four are the same 401 body.
     *
     * Expiry is checked before the repository read, so a dead session never costs a database
     * round trip.
     *
     * The read must stay outside a unit of work. SqlUserRepository.byId consults the identity
     * map first, so a revalidation performed inside an open unit of work could be answered
     * from a cached instance - that is, from the very snapshot it exists to check. The
     * interceptor calls this from preHandle, before any unit of work is opened.
     *
     * The check runs per request, so no request that BEGINS after a user is deleted can carry
     * that user. A request already past preHandle finishes with the authority it was granted;
     * that residual window is one handler duration and closing it would mean re-reading the
     * user inside every unit of work, which is not worth what it costs.
     */
    public Optional<User> resolve(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();

        Entry entry = sessions.get(sessionId);
        if (entry == null) return Optional.empty();

        Instant now = clock.instant();
        if (entry.hasExpired(now)) {
            sessions.remove(sessionId);
            return Optional.empty();
        }

        // A store failure must not reach here as an empty Optional. It propagates instead, and
        // is answered 500, precisely so that a database blip degrades rather than signing
        // every user in the building out at once.
        Optional<User> found = users.byId(entry.userId);
        if (found.isEmpty()) {
            sessions.remove(sessionId);
            // Worth a line, unlike a missing header or an expiry: a live session pointing at a
            // user who no longer exists is not something ordinary operation produces, and it
            // happens at most once per session because the entry goes with it. The user id is
            // safe to log; the session id would be a credential in a log file.
            AppLogger.warn("auth", "Closed a session whose user " + entry.userId
                    + " no longer exists");
            return Optional.empty();
        }

        User user = found.get();
        // The id alone is not identity. Ids are handed out by a sequence, but a row deleted
        // and re-created out of band - a restore, a fixture reload, a hand fix-up - can put a
        // different person on the same id, and out-of-band writes are exactly the threat this
        // whole item is about. Without this, a session issued against one password would
        // authenticate as whoever now holds that id. Username is the discriminator because it
        // is UNIQUE in the schema and because it is the value the credential check matched.
        if (!entry.username.equals(user.username())) {
            sessions.remove(sessionId);
            AppLogger.warn("auth", "Closed a session whose user id " + entry.userId
                    + " now names a different user");
            return Optional.empty();
        }

        entry.lastSeenAt = now;
        return found;
    }

    /**
     * Closes the session with the given id if it exists.
     */
    public void remove(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /** Entries held, expired ones included until something sweeps them. For the tests. */
    int size() {
        return sessions.size();
    }

    /**
     * What a session is: whose it is, and when it started and was last used. Nothing else. No
     * User, so no role, no customer id and no password material outlives the login that
     * created it - every one of those is read from the row on each request instead.
     *
     * Note the trade that comes with that. The credential columns leave this map, but
     * SqlUserRepository selects them on every read, so on the SQL backend they now cross the
     * connection once per authenticated request rather than once per login. Narrowing that
     * would mean a projection that builds a User with credential fields it does not have,
     * which is a worse thing to hand to the security context than an unused byte array.
     *
     * lastSeenAt is volatile rather than guarded: two concurrent requests on one session both
     * write approximately-now, and no decision depends on which of them wins.
     */
    private static final class Entry {
        final int userId;
        final String username;
        final Instant createdAt;
        volatile Instant lastSeenAt;

        Entry(int userId, String username, Instant createdAt) {
            this.userId = userId;
            this.username = username;
            this.createdAt = createdAt;
            this.lastSeenAt = createdAt;
        }

        /** Inclusive at the deadline: a session is dead at exactly its timeout, not after it. */
        boolean hasExpired(Instant now) {
            return !now.isBefore(createdAt.plus(MAX_LIFETIME))
                    || !now.isBefore(lastSeenAt.plus(IDLE_TIMEOUT));
        }
    }
}
