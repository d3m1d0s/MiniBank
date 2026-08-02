package cz.vsb.minibank.api;

import cz.vsb.minibank.application.AuthService;
import cz.vsb.minibank.application.SessionStore;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.exceptions.ValidationException;
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
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
public class AuthController {

    private final AuthService authService;
    private final SessionStore sessions;

    public AuthController(AuthService authService, SessionStore sessions) {
        this.authService = authService;
        this.sessions = sessions;
    }

    /**
     * Authenticates the user and returns a new session ID.
     */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        // A missing field is a malformed request, not a failed credential check: sharing a
        // type with AuthService would make the 401 body reachable by omitting a field.
        if (req.username() == null || req.password() == null) {
            throw new ValidationException("Username and password are required");
        }

        User u = authService.login(req.username(), req.password().toCharArray());

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
