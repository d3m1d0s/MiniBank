package cz.vsb.minibank.api.web;

import cz.vsb.minibank.application.auth.SecurityContext;
import cz.vsb.minibank.application.auth.SessionStore;
import cz.vsb.minibank.domain.customer.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Handler interceptor that resolves the user from X-Session-Id and populates the security context.
 */
@Component
public class SessionAuthInterceptor implements HandlerInterceptor {

    private final SessionStore sessions;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionAuthInterceptor(SessionStore sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws Exception {

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();

        // Exactly one path is reachable without a session, and it is matched exactly. The
        // prefix this replaces exempted everything under /api/auth/, which is how logout came
        // to terminate any session id supplied by any caller, and would have silently exempted
        // whatever endpoint was added there next.
        if ("/api/auth/login".equals(path)) {
            return true;
        }

        // Neither refusal below is logged, and the second one used to be. A request with no
        // session header is what an unauthenticated browser does routinely; a rejected id is
        // now equally routine, because sessions expire and every request after that carries a
        // dead one. Either would let any caller drive an unbounded, unrotated log file at
        // request rate, and neither carries a signal worth that. SessionStore writes the one
        // line that does mean something - a live session whose user has been deleted or
        // replaced - once per session, and never with the session id in it.
        String sessionId = request.getHeader("X-Session-Id");
        if (sessionId == null || sessionId.isBlank()) {
            writeUnauthorized(response);
            return false;
        }

        // Resolves against the users table, not against a login-time snapshot: a deleted user
        // fails here, and a role or customer id changed since login is the one this request is
        // authorized with.
        User user = sessions.resolve(sessionId)
                .orElse(null);

        if (user == null) {
            writeUnauthorized(response);
            return false;
        }

        SecurityContext.setCurrentUser(user);
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex
    ) {
        SecurityContext.clear();
    }

    /**
     * preHandle returns false instead of throwing, so this response never reaches the
     * handler advice. The payload comes from the shared catalogue for that reason.
     *
     * A missing header, an unknown session id, an expired one and one whose user is gone all
     * produce the identical body: telling any of them apart would answer "is this session id
     * one you have ever issued?".
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), ApiErrors.AUTH_REQUIRED);
    }
}
