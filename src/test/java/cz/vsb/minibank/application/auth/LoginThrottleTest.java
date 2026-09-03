package cz.vsb.minibank.application.auth;

import cz.vsb.minibank.domain.exceptions.TooManyLoginAttemptsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import cz.vsb.minibank.application.TestClock;

/**
 * What this replaced was nothing at all: alice signed in
 * normally after forty consecutive failures.
 *
 * Everything here is asserted against the counter rather than over HTTP, so no case costs a
 * PBKDF2 hash and the cap can be reached in milliseconds. The wire contract has its own case in
 * HttpErrorContractTest. The clock is advanced, never slept on: the window has to pass inside
 * one instance, because the counters are what is under test.
 */
class LoginThrottleTest {

    private static final Instant T0 = Instant.parse("2026-01-01T09:00:00Z");
    private static final String ATTACKER = "198.51.100.7";
    private static final String ALICE = "203.0.113.4";

    private TestClock clock;
    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        clock = new TestClock(T0);
        throttle = new LoginThrottle(clock);
    }

    /** One attempt whose password turned out to be wrong: taken and never given back. */
    private void failedSignIn(String origin) {
        throttle.requireAttemptAllowed(origin);
    }

    private void failedSignIns(String origin, int times) {
        for (int i = 0; i < times; i++) failedSignIn(origin);
    }

    /**
     * An attacker who keeps going after being refused. Their own refusals are swallowed, which
     * is the whole point of the cases that use this: what happens to them must not reach
     * anybody else.
     */
    private void hammer(String origin, int times) {
        for (int i = 0; i < times; i++) {
            try {
                throttle.requireAttemptAllowed(origin);
            } catch (TooManyLoginAttemptsException refused) {
                // Expected, once the allowance is gone.
            }
        }
    }

    /** One attempt whose password was right: taken at the gate, returned afterwards. */
    private void successfulSignIn(String origin) {
        throttle.requireAttemptAllowed(origin);
        throttle.releaseSuccessfulAttempt(origin);
    }

    // ------------------------------------------------------------ the honest user

    /**
     * The case the prompt asks about by name. A single mistype must be invisible - if the
     * throttle is felt by somebody having a bad morning it is a worse deal for them than for
     * the attacker, who does not mind waiting.
     */
    @Test
    void oneMistypeChangesNothingForTheUserWhoMadeIt() {
        failedSignIn(ALICE);

        assertDoesNotThrow(() -> throttle.requireAttemptAllowed(ALICE),
                "one wrong password must cost the next attempt nothing");
    }

    @Test
    void aHandfulOfMistypesStillCostsNothing() {
        failedSignIns(ALICE, 3);

        assertDoesNotThrow(() -> throttle.requireAttemptAllowed(ALICE));
    }

    // ------------------------------------------------------------ the limit itself

    @Test
    void theAttemptAfterTheAllowanceIsSpentIsRefused() {
        failedSignIns(ATTACKER, LoginThrottle.MAX_FAILURES - 1);
        assertDoesNotThrow(() -> throttle.requireAttemptAllowed(ATTACKER),
                "the last of the allowance must still be granted");

        assertThrows(TooManyLoginAttemptsException.class,
                () -> throttle.requireAttemptAllowed(ATTACKER),
                "the attempt after MAX_FAILURES must be refused");
    }

    /**
     * The property that makes this a throttle rather than an account lockout, and the reason
     * the key is an origin and not a username. Nothing an attacker does to their own counter
     * may reach the customer they are guessing at - which is also why nothing in this class
     * takes a username at all.
     */
    @Test
    void oneOriginCannotSpendAnothersAllowance() {
        hammer(ATTACKER, LoginThrottle.MAX_FAILURES * 4);

        assertDoesNotThrow(() -> throttle.requireAttemptAllowed(ALICE));
    }

    // ------------------------------------------------------------ the window

    @Test
    void theWindowExpiresAndTheCallerMayTryAgain() {
        failedSignIns(ATTACKER, LoginThrottle.MAX_FAILURES);
        assertThrows(TooManyLoginAttemptsException.class,
                () -> throttle.requireAttemptAllowed(ATTACKER));

        clock.advance(LoginThrottle.WINDOW);

        assertDoesNotThrow(() -> throttle.requireAttemptAllowed(ATTACKER),
                "time, and only time, is what gives the allowance back");
    }

    /**
     * The window is anchored at the first counted attempt, so retrying while refused does not
     * push the release further away - an honest user will retry impatiently, and must not be
     * punished for it. It also means the wait after being refused is normally far less than a
     * full window, which is why the catalogue message says "a few minutes".
     */
    @Test
    void aRefusedAttemptDoesNotExtendTheWindow() {
        failedSignIns(ATTACKER, LoginThrottle.MAX_FAILURES);

        clock.advance(LoginThrottle.WINDOW.minusSeconds(1));
        assertThrows(TooManyLoginAttemptsException.class,
                () -> throttle.requireAttemptAllowed(ATTACKER));

        clock.advance(Duration.ofSeconds(1));
        assertDoesNotThrow(() -> throttle.requireAttemptAllowed(ATTACKER));
    }

    // ------------------------------------------------------------ what success does

    /**
     * A successful sign-in returns the one unit that attempt took, and clears nothing else.
     *
     * Note this is deliberately NOT "a successful login resets the count". Resetting would
     * hand the bypass to anyone holding one valid password - in a bank, every customer, and in
     * this project anyone who has read the README, since both sign-in screens put the demo
     * credentials on the page. Such a caller would interleave a login of their own every tenth
     * guess and never be refused at all, and the throttle would bound nothing against exactly
     * the attacker most likely to exist. So the budget of failures per window is fixed, and
     * this test pins that: three failures plus a success still leave only MAX_FAILURES - 3.
     */
    @Test
    void aSuccessfulSignInGivesBackItsOwnAttemptAndNoMore() {
        failedSignIns(ALICE, 3);

        successfulSignIn(ALICE);

        // The remaining budget is MAX_FAILURES minus the three failures, and not more.
        failedSignIns(ALICE, LoginThrottle.MAX_FAILURES - 3);

        assertThrows(TooManyLoginAttemptsException.class,
                () -> throttle.requireAttemptAllowed(ALICE),
                "a success must not buy back somebody's earlier failures");
    }

    /**
     * The other half, and the reason the release exists at all. A page reload discards the
     * session id, so repeated legitimate sign-ins from one machine are ordinary here; counting
     * attempts rather than failures would throttle a demo within a minute.
     */
    @Test
    void repeatedSuccessfulSignInsAreNeverThrottled() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES * 3; i++) {
            int attempt = i;
            assertDoesNotThrow(() -> successfulSignIn(ALICE),
                    "successful sign-in " + attempt + " must not be refused");
        }
    }

    /** And a caller who only ever succeeds leaves nothing behind to bound. */
    @Test
    void aSuccessfulSignInLeavesNoEntry() {
        successfulSignIn(ALICE);

        assertEquals(0, throttle.size());
    }

    // ------------------------------------------------------------ concurrency

    /**
     * The bound has to hold at every instant, not on average, and this is the case that pins
     * it. Checking the counter and then taking the allowance after the password had been
     * hashed would leave a gap of the better part of a second between the two; every request the
     * servlet container will run at once - two hundred, by Tomcat's default - could pass the
     * same stale reading, and one origin would get MAX_FAILURES plus the whole thread pool of
     * guesses per window, and force that many PBKDF2 hashes with them.
     *
     * Sixty-four callers race for ten units. Exactly ten may win, whatever the scheduler does.
     */
    @Test
    void concurrentAttemptsCannotOverspendTheAllowance() throws Exception {
        int callers = 64;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> running = new ArrayList<>();

        try {
            for (int i = 0; i < callers; i++) {
                running.add(pool.submit(() -> {
                    start.await();
                    try {
                        throttle.requireAttemptAllowed(ATTACKER);
                        allowed.incrementAndGet();
                    } catch (TooManyLoginAttemptsException refused) {
                        // The expected outcome for all but MAX_FAILURES of them.
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : running) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(LoginThrottle.MAX_FAILURES, allowed.get(),
                "exactly the allowance may be spent, however many callers arrive together");
    }

    // ------------------------------------------------------------ the map stays bounded

    /** No background thread: the sweep runs inside the branch that can insert. */
    @Test
    void elapsedWindowsAreSweptWhenANewOriginArrives() {
        for (int i = 0; i < 50; i++) failedSignIn("10.0.0." + i);
        assertEquals(50, throttle.size());

        clock.advance(LoginThrottle.WINDOW);
        failedSignIn(ATTACKER);

        assertEquals(1, throttle.size(), "every window that had run out must be gone");
    }

    /**
     * The cap, and the direction it fails in. A flood wide enough to fill the map must never be
     * able to refuse a login: that would let anyone holding a thousand addresses shut the front
     * door on every customer, which is a far larger harm than the guessing this bounds.
     */
    @Test
    void theCapEvictsRatherThanRefusingAnybody() {
        fillToTheCap();
        assertEquals(LoginThrottle.MAX_TRACKED_ORIGINS, throttle.size());

        assertDoesNotThrow(() -> throttle.requireAttemptAllowed(ATTACKER),
                "a newly seen origin must always be tracked, never refused");

        assertEquals(LoginThrottle.MAX_TRACKED_ORIGINS, throttle.size(), "still capped");
        assertDoesNotThrow(() -> throttle.requireAttemptAllowed(ALICE),
                "and no honest caller is refused because the map is full");
    }

    /**
     * Which entry is dropped, and why it must not be the oldest outright. An exhausted window
     * is by construction one that has been open longest, so evicting by age alone would
     * preferentially drop exactly the callers currently being refused and hand their spent
     * allowance straight back - an attacker with a thousand and one addresses could then
     * refund their own counter on demand. The victim is the oldest window that still has room
     * left instead.
     */
    @Test
    void makingRoomKeepsAnExhaustedWindowAndDropsOneWithRoomLeft() {
        // Spent first, so its window is the oldest in the map and an age-only rule would pick it.
        failedSignIns(ATTACKER, LoginThrottle.MAX_FAILURES);
        assertThrows(TooManyLoginAttemptsException.class,
                () -> throttle.requireAttemptAllowed(ATTACKER));

        clock.advance(Duration.ofMillis(1));
        for (int i = 0; i < LoginThrottle.MAX_TRACKED_ORIGINS - 1; i++) {
            failedSignIn(spareOrigin(i));
            clock.advance(Duration.ofMillis(1));
        }
        assertEquals(LoginThrottle.MAX_TRACKED_ORIGINS, throttle.size());

        // One more origin forces an eviction.
        failedSignIn("198.51.100.99");

        assertThrows(TooManyLoginAttemptsException.class,
                () -> throttle.requireAttemptAllowed(ATTACKER),
                "making room must not give a spent counter back");
    }

    private void fillToTheCap() {
        for (int i = 0; i < LoginThrottle.MAX_TRACKED_ORIGINS; i++) {
            failedSignIn(spareOrigin(i));
            clock.advance(Duration.ofMillis(1));
        }
    }

    private static String spareOrigin(int i) {
        return "10." + (i / 256) + "." + (i % 256) + ".1";
    }
}
