package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.exceptions.TooManyLoginAttemptsException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Counts recent sign-in attempts per caller and refuses further ones from a caller whose
 * allowance is spent, so that guessing a password costs more than one POST per guess.
 *
 * What this replaces is nothing at all: alice signed in
 * normally after forty consecutive failures.
 *
 * <h2>Keyed by where the attempt came from, never by the username</h2>
 *
 * Two reasons, and both of them are the point of this class rather than a detail of it.
 *
 * The first is that a counter keyed by a username is keyed by a string anybody may type, so
 * anybody could spend a named customer's allowance on purpose and keep them out of their own
 * account for as long as they cared to keep failing. Nothing in this product does that today
 * and a throttle must not be how it arrives. Keyed by origin, a spent counter can only ever
 * reach the caller who spent it - the rule SessionStore states as "nobody may be signed out
 * because somebody else signed in", with the noun changed. It is also the trap the session
 * rules avoid:
 * the map is not keyed on a string an anonymous caller writes into a JSON body.
 *
 * The second is that a username is a fact about the users table. A throttle that consulted one
 * would have to decide what to do about a name that is not there, and either answer would tell
 * a caller which names are - reopening by the side door what hashing a stand-in for an unknown
 * user, and answering one body for four session failures, were done to close.
 * Nothing here takes a username, so there is nothing here to leak.
 *
 * <h2>What "origin" is, and what it is not</h2>
 *
 * The caller passes {@code HttpServletRequest.getRemoteAddr()}: the peer of the TCP
 * connection. application.properties pins {@code server.forward-headers-strategy=none} so that
 * this cannot silently become {@code X-Forwarded-For}, which the caller writes and could vary
 * per request - that would make the key attacker-typed after all, and the throttle a no-op.
 * Turning that property on requires a trusted-proxy configuration first.
 *
 * <b>The load-bearing caveat: on this project's deployment there is one bucket for
 * everybody.</b> Both frontends post through their own vite proxy with
 * {@code changeOrigin: true}, and docker-compose.yml runs Postgres and nothing else, so every
 * sign-in this project can produce reaches Spring from a loopback address. That used to be true
 * of minibank-web for a different reason - it named {@code http://localhost:8080} outright - and
 * the conclusion did not depend on which. Behind a reverse proxy - the ordinary way a Boot
 * application is exposed - the same collapse happens for every customer at once. So ten failed
 * sign-ins can refuse everyone for the rest of the window. That is worth stating plainly
 * rather than filing as a development-environment note, and it is the principal residual risk
 * of this design, and it is accepted rather than mitigated. The alternative was weighed and
 * refused: keying on the username instead would bound guessing per account, and would hand
 * any caller a way to lock a named customer out of their own account for as long as they
 * cared to keep failing - the denial of service this class is keyed on the origin to avoid.
 * Loopback is deliberately <i>not</i>
 * exempted, because exempting it would make the throttle inert in the only environment this
 * project actually runs in, and would also disable it entirely for any deployment whose
 * reverse proxy sits on the same host.
 *
 * <h2>What it bounds and what it does not</h2>
 *
 * Bounded, exactly: failed sign-ins from one origin inside one window, at most
 * {@link #MAX_FAILURES}; and PBKDF2 hashes running concurrently for one origin, also at most
 * {@link #MAX_FAILURES}, because the allowance is taken at the gate rather than after the
 * hash. Taking it afterwards would have left a ~300 ms hole between the check and the count in
 * which the whole servlet thread pool - 200 threads by Tomcat's default, which nothing here
 * overrides - could pass a gate that had already been spent.
 *
 * Not bounded, deliberately: the cost of repeated <i>successful</i> sign-ins. Counting
 * attempts rather than failures would throttle the legitimate repeated logins a page reload
 * produces, so a caller holding any valid password can still spend CPU one hash at a time.
 * Nor are sign-ins that failed on this application's own side, for the reason
 * {@link #releaseAttemptThatWasNotAGuess} sets out; the condition that makes them repeatable
 * is one in which nothing else works either.
 * Also not bounded: guesses made against one account from many origins, and guesses from an
 * attacker holding more distinct addresses than {@link #MAX_TRACKED_ORIGINS}, who can recycle
 * them so that every guess lands on a freshly created window. Bounding either needs the
 * username key, which costs the denial of service above, or address reputation. The second is
 * a deliberate scope boundary of this project rather than an oversight, and it sits with
 * account lockout and CAPTCHA: all three need a data source this deployment does not have,
 * and all three are product capabilities rather than fixes to what is here. A single origin
 * drops from roughly three hundred thousand guesses a day to a thousand; the rest is written
 * down rather than fixed.
 *
 * <h2>Not logged, on purpose</h2>
 *
 * A refusal happens once per attempt and an attempt is one unauthenticated POST, so a line per
 * refusal would let any caller drive the unrotated log file at request rate - what
 * SessionAuthInterceptor declines to do for its two 401 paths - and would cost far more than
 * the check it described. The throttle in fact makes the log quieter: the line the advice
 * writes for a refused sign-in is now written for at most {@link #MAX_FAILURES} attempts per
 * origin per window instead of for all of them. The cost is that the throttle firing leaves no
 * trace at all.
 *
 * <h2>Measuring the login path after this</h2>
 *
 * The repro for this was twenty interleaved sign-ins from one machine, which this refuses from
 * the eleventh on. A live timing measurement of the login path must therefore drive
 * {@link AuthService} directly - which is where the constant-work guarantee lives and where
 * AuthServiceTest already asserts it - or space its attempts across windows. There is no
 * runtime reset: only time, or restarting the process, clears a spent counter.
 */
public final class LoginThrottle {

    /**
     * Failed sign-ins one origin may spend inside a window before the next attempt is refused.
     *
     * Set well above a person having a bad morning: three or four typos in a row must cost
     * nothing at all, or this would be a worse experience for the honest user than for the
     * attacker, who does not mind waiting. Ten wrong passwords from one machine inside a
     * quarter of an hour is no longer a typo.
     *
     * Also set well above two, because AuthServiceTest drives two consecutive failures to
     * prove an unknown username still pays for a hash, and because both frontends, the demo
     * runner and any hand-written request share one bucket here - see the class comment.
     */
    public static final int MAX_FAILURES = 10;

    /**
     * How long those failures are remembered. The same span as SessionStore.IDLE_TIMEOUT,
     * because it is the same kind of quantity - long enough to be a real bound, short enough
     * that waiting it out is an inconvenience rather than a support call - and one number is
     * easier to explain than two.
     *
     * Anchored at the first attempt counted in it and never extended, so a caller who keeps
     * hammering while refused does not push their own release further away; they are let back
     * in at the same instant either way. It also means the wait after being refused is
     * normally much less than a full window, which is why the message in ApiErrors says "a few
     * minutes" rather than naming this number.
     */
    public static final Duration WINDOW = Duration.ofMinutes(15);

    /**
     * Origins tracked at once.
     *
     * Not chosen to match SessionStore.MAX_SESSIONS, although it happens to: that cap bounds a
     * store of successful logins, this one bounds how many distinct callers can be remembered
     * at once, and the symmetry would be the wrong reason. The number is what it is because
     * the sweep below is O(n) and runs under this object's monitor on the path that inserts, so
     * a much larger map would trade a bound on guessing for a lock convoy on the login path -
     * a thousand entries scan in microseconds beside a hash that costs hundreds of
     * milliseconds, and cost about a hundred kilobytes.
     *
     * What it does not do is stop an attacker who holds more addresses than this. They can
     * cycle them so that every guess creates a fresh window and nothing is ever refused; at
     * 1.1 requests per second that is not a flood, it is simply the distributed attacker this
     * project does not set out to stop, for the reasons given above. Do not read this cap as a
     * defence against them.
     *
     * Reaching it evicts rather than refusing the login, which is the opposite of what
     * SessionStore does at its cap, because the harm is the opposite way round. Failing shut
     * here would let any host with a thousand addresses close the bank's front door to every
     * customer - a bound reaching people who did not cause it, which is the one thing neither
     * this cap nor that one may do. So the throttle fails open and degrades instead of
     * breaking.
     */
    public static final int MAX_TRACKED_ORIGINS = 1_000;

    /**
     * A plain HashMap and not a ConcurrentHashMap, unlike SessionStore's. Every path that
     * touches this map holds this object's monitor, so a concurrent map would advertise a
     * lock-free read path that deliberately does not exist here - see
     * {@link #requireAttemptAllowed}.
     */
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;

    /**
     * @param clock supplies "now". Its zone is left alone, as SessionStore's is: this is an
     *              elapsed-time bound on an Instant with none of the calendar boundary that
     *              makes the transfer service force a zone. The only reason to pass anything
     *              but the system clock is a test that has to let a window pass without
     *              waiting a quarter of an hour for it.
     */
    public LoginThrottle(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Takes one unit of this origin's allowance, or refuses the attempt if it has none left.
     *
     * Called before the credential is checked, which is what makes this a bound on work and
     * not only on answers: a refused attempt costs a map lookup instead of 120 000 PBKDF2
     * iterations, five megabytes of garbage, a log line and, on the SQL backend, a fresh
     * database connection.
     *
     * The allowance is taken here and not after the failure, and that ordering is the whole
     * correctness of this class. A check that only read the counter would be separated from
     * the write that follows it by a full password hash - hundreds of milliseconds - and every
     * request the servlet container will run concurrently could pass it on the same stale
     * reading. The bound would then be MAX_FAILURES plus the size of the thread pool, which is
     * twenty times the intended number, and the same factor would apply to the hashes an
     * origin can force. Counting at the gate, under the lock, makes the bound hold at every
     * instant whatever any client does.
     *
     * Synchronized for that reason, and because the expiry check, the sweep, the cap and the
     * insert are one decision over the whole map. The lock is affordable because only the
     * login path reaches it and every caller that gets past it immediately spends a PBKDF2
     * hash outside it; the O(n) sweep is confined to the branch that inserts, so a refused
     * attempt stays O(1) and cannot be used to drive a scan at request rate.
     *
     * @throws TooManyLoginAttemptsException when this origin has no attempts left in its window
     */
    public synchronized void requireAttemptAllowed(String origin) {
        String key = key(origin);
        Instant now = clock.instant();

        Window window = windows.get(key);
        if (window != null && window.hasElapsed(now)) {
            windows.remove(key);
            window = null;
        }

        if (window != null) {
            if (window.count >= MAX_FAILURES) {
                // No username in the message, and none available: this object never sees one.
                throw new TooManyLoginAttemptsException(
                        "Sign-in attempts are exhausted for the current window");
            }
            window.count++;
            return;
        }

        makeRoomFor(now);
        windows.put(key, new Window(now));
    }

    /**
     * Gives back the one unit the attempt that has just proved its password took, and nothing
     * else.
     *
     * This is not "clear the counter on success", which would hand the bypass to anyone
     * holding one valid password - in a bank, every customer, and in this project anyone who
     * has read the README, since LoginDialog.tsx prints the demo credentials on the screen.
     * Such a caller would interleave a login of their own every tenth guess and never be
     * refused at all. Accumulated failures are cleared by time and by nothing else: an honest
     * user's typos are forgotten a quarter of an hour after the first one whether or not they
     * eventually got in.
     *
     * What this does do is stop the throttle counting legitimate repeated sign-ins as
     * failures. It has to: SessionStore's own comment notes that the session id "lives in a
     * module-level variable that a page reload discards", so a demo or a development session
     * involves many logins in quick succession, all from one address. Ten of those in fifteen
     * minutes must not be refused.
     *
     * A window that drops to nothing is removed, so a caller who only ever signs in
     * successfully leaves no entry and consumes none of {@link #MAX_TRACKED_ORIGINS}.
     *
     * One honest caveat. The release finds the origin's window rather than the exact window
     * the attempt was counted in, so if a window elapses while a password is being hashed and
     * another caller at the same origin opens a fresh one meanwhile, this can cancel that
     * caller's failure instead. The slop is one count, it needs a window boundary to fall
     * inside a single hash, and the quantity being bounded is a rate.
     */
    public synchronized void releaseSuccessfulAttempt(String origin) {
        giveBack(origin);
    }

    /**
     * Gives back the unit an attempt took when that attempt never became a guess.
     *
     * The quantity this class bounds is failed sign-ins, and a request that never reached the
     * password comparison has not failed one. On the SQL backend a database that is down makes
     * SqlUserRepository throw before any credential is looked at, and the caller is answered
     * 500; that is a report about this application, not about anybody's password. Counting it
     * turns a short outage into a much longer one: with the single bucket described above,
     * ten such attempts refuse every customer's sign-in for the rest of the window, which only
     * time clears, so the refusals outlive the outage that produced them by up to a quarter of
     * an hour. The design notes above settle the take-at-gate ordering and the shared origin
     * and are silent on this case, so it is recorded here rather than inferred from them.
     *
     * Deliberately identical to {@link #releaseSuccessfulAttempt} and deliberately incapable
     * of telling the two apart: only the caller knows which happened, and it is a separate
     * method purely so that neither call site has to be read against the other's name.
     *
     * The caller must not reach this for an authentication failure. Releasing an unknown
     * username but not a wrong password - or the other way round - would let anyone read the
     * users table off the counter, which is the leak AuthService hashes a stand-in to close.
     */
    public synchronized void releaseAttemptThatWasNotAGuess(String origin) {
        giveBack(origin);
    }

    private void giveBack(String origin) {
        String key = key(origin);
        Window window = windows.get(key);
        if (window == null) return;
        if (--window.count <= 0) {
            windows.remove(key);
        }
    }

    /**
     * Drops every window that has run out and, if the map is at its cap, evicts one live
     * window to make room. Runs only from the branch of {@link #requireAttemptAllowed} that
     * inserts, which is the only thing that can grow this map. There is no background sweep
     * and none is needed; the same honest caveat SessionStore writes down applies - nothing
     * sweeps unless somebody attempts a sign-in - which is why the cap, not the window, is
     * what actually bounds the map.
     *
     * The victim is the oldest window that still has allowance left, and only if every live
     * window is exhausted is an exhausted one taken. Choosing the oldest outright would be
     * exactly wrong: an exhausted window is by construction one that has been open longest, so
     * "oldest" preferentially evicts the callers currently being refused and hands their
     * allowance straight back. Preferring a window with room left cannot refund anybody's
     * spent guesses, and it still never refuses the newcomer.
     */
    private void makeRoomFor(Instant now) {
        String oldestWithRoom = null;
        Instant oldestWithRoomAt = null;
        String oldestOfAll = null;
        Instant oldestOfAllAt = null;

        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Window> e = it.next();
            Window w = e.getValue();
            if (w.hasElapsed(now)) {
                it.remove();
                continue;
            }
            if (oldestOfAllAt == null || w.startedAt.isBefore(oldestOfAllAt)) {
                oldestOfAllAt = w.startedAt;
                oldestOfAll = e.getKey();
            }
            if (w.count < MAX_FAILURES
                    && (oldestWithRoomAt == null || w.startedAt.isBefore(oldestWithRoomAt))) {
                oldestWithRoomAt = w.startedAt;
                oldestWithRoom = e.getKey();
            }
        }

        if (windows.size() < MAX_TRACKED_ORIGINS) return;

        String victim = oldestWithRoom != null ? oldestWithRoom : oldestOfAll;
        if (victim != null) {
            windows.remove(victim);
        }
    }

    /**
     * A missing origin shares one counter rather than escaping the throttle. Nothing in the
     * servlet container produces one, and if anything ever does, sharing is the safe direction.
     */
    private static String key(String origin) {
        return origin == null || origin.isBlank() ? "<unknown>" : origin;
    }

    /** Origins with a live window. For the tests. */
    synchronized int size() {
        return windows.size();
    }

    /**
     * What a window is: when it opened, and how much of the allowance has been taken in it.
     * Not a record, because the count is incremented in place. Neither field is volatile and
     * neither needs to be: nothing reads this map outside the owning object's monitor.
     */
    private static final class Window {
        final Instant startedAt;
        int count;

        Window(Instant startedAt) {
            this.startedAt = startedAt;
            this.count = 1;
        }

        /** Inclusive at the deadline, as SessionStore's expiry is: over at exactly WINDOW. */
        boolean hasElapsed(Instant now) {
            return !now.isBefore(startedAt.plus(WINDOW));
        }
    }
}
