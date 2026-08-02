package cz.vsb.minibank.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the test moves by hand.
 *
 * DailyLimitTest gets away with Clock.fixed because it rebuilds the transfer service for each
 * instant and shares the repositories. That does not work for a session store: the sessions
 * live in the store, so rebuilding it loses the thing under test and time has to advance
 * inside one instance.
 *
 * withZone returns a copy that no longer shares this clock's mutable now, which is exactly why
 * SessionStore must not call it - doing so would silently freeze the store's time at whatever
 * instant the copy was taken.
 *
 * Public because it is used from two test packages: SessionStoreTest here and
 * HttpErrorContractTest in cz.vsb.minibank.api.
 */
public final class TestClock extends Clock {

    private final ZoneId zone;
    private Instant now;

    public TestClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private TestClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public void advance(Duration by) {
        now = now.plus(by);
    }

    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId other) { return new TestClock(now, other); }
    @Override public Instant instant() { return now; }
}
