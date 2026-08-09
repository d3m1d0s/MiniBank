package cz.vsb.minibank.api;

import cz.vsb.minibank.application.AuthService;
import cz.vsb.minibank.application.LoginThrottle;
import cz.vsb.minibank.application.SessionStore;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.exceptions.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * Request payload for authentication.
 */
record LoginRequest(String username, String password) {}

/**
 * Response payload for authentication containing session and identity information.
 */
record LoginResponse(String sessionId, String username, String role, Integer customerId) {}

/**
 * REST controller for authentication and session management.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionStore sessions;

    /**
     * Consulted here rather than inside {@link AuthService} because it needs the origin of
     * the request, and AuthService is handed a username and a password and nothing else.
     * Keeping it out of AuthService also leaves the constant-work guarantee and the test that
     * pins it untouched, and leaves the console's own login loop - which has no network peer to
     * key on - unchanged.
     */
    private final LoginThrottle throttle;

    public AuthController(AuthService authService, SessionStore sessions, LoginThrottle throttle) {
        this.authService = authService;
        this.sessions = sessions;
        this.throttle = throttle;
    }

    /**
     * Authenticates the user and returns a new session ID.
     */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req, HttpServletRequest http) {
        // A missing field is a malformed request, not a failed credential check: sharing a
        // type with AuthService would make the 401 body reachable by omitting a field.
        // Checked before the throttle, so a malformed request still costs nothing and still
        // cannot spend anybody's allowance.
        if (req.username() == null || req.password() == null) {
            throw new ValidationException("Username and password are required");
        }

        // getRemoteAddr, never X-Forwarded-For: application.properties pins
        // server.forward-headers-strategy=none, so this is the peer of the connection rather
        // than a header the caller writes - which it has to be, or the throttle's key would be
        // one more string the attacker types and every request could arrive with a fresh
        // counter.
        String origin = http.getRemoteAddr();

        // Before the credential check, so a refused attempt costs a map lookup rather than
        // 120 000 PBKDF2 iterations, five megabytes of garbage and, in sql mode, a database
        // connection. The allowance is taken here rather than given back on failure, so that
        // requests already in flight cannot all pass a gate that has been spent; see
        // LoginThrottle.requireAttemptAllowed.
        throttle.requireAttemptAllowed(origin);

        User u = authService.login(req.username(), req.password().toCharArray());

        // The password was right, so the unit this attempt took is returned - and only that
        // one. Earlier failures at this origin are still counted, because the alternative
        // would let anyone holding one valid password interleave a login of their own every
        // tenth guess and never be refused. Placed above createSession on purpose: a caller
        // whose credential was correct must not be charged for a full session store.
        throttle.releaseSuccessfulAttempt(origin);

        // In a finally because createSession can now refuse. AuthService.login has already put
        // the user in the security context, and leaving it there on a thread that is about to
        // be returned to the pool would let the next request start out signed in as whoever
        // last failed to open a session here.
        try {
            String sessionId = sessions.createSession(u);
            return new LoginResponse(sessionId, u.username(), u.role().name(), u.customerId());
        } finally {
            SecurityContext.clear();
        }
    }

    /**
     * Closes the caller's own session.
     *
     * The interceptor guards this path, so the id in the header is one the store resolved to a
     * live user a moment ago: a caller can only close the session it is already holding. While
     * the exemption was a prefix over /api/auth/, this endpoint authenticated nobody and would
     * terminate any session id at all for anyone who asked.
     *
     * The header stays optional even though the interceptor now makes a missing one
     * unreachable. Requiring it would make Spring throw a checked ServletException that the
     * advice cannot catch, putting a body with no code field on the wire and breaking the
     * error contract to guard a case that cannot happen.
     */
    @PostMapping("/logout")
    public void logout(@RequestHeader(name = "X-Session-Id", required = false) String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
        authService.logout();
    }
}
