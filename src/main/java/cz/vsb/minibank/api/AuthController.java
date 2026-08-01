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
        String sessionId = sessions.createSession(u);

        SecurityContext.clear();

        return new LoginResponse(sessionId, u.username(), u.role().name(), u.customerId());
    }

    /**
     * Logs the user out and removes the session if the session ID is provided.
     */
    @PostMapping("/logout")
    public void logout(@RequestHeader(name = "X-Session-Id", required = false) String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
        authService.logout();
    }
}
