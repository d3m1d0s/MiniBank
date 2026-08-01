package cz.vsb.minibank.api;

import cz.vsb.minibank.application.AppLogger;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.application.SessionStore;
import cz.vsb.minibank.domain.User;
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

        if (path.startsWith("/api/auth/")) {
            return true;
        }

        String sessionId = request.getHeader("X-Session-Id");
        if (sessionId == null || sessionId.isBlank()) {
            // Not logged, on purpose. A request with no session header at all is what an
            // unauthenticated browser does routinely; it carries no security signal, and
            // logging it would let an anonymous caller drive an unbounded, unrotated log
            // file at request rate.
            writeUnauthorized(response);
            return false;
        }

        User user = sessions.findUser(sessionId)
                .orElse(null);

        if (user == null) {
            AppLogger.warn("api", "Rejected a request with an unknown session id: " + forLog(path));
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
     * A missing header and an unknown session id produce the identical body: telling the
     * two apart would answer "is this session id one you have ever issued?".
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), ApiErrors.AUTH_REQUIRED);
    }

    /**
     * The request URI is attacker-controlled and bounded only by the container's request
     * line limit, so it is truncated and stripped before it reaches the log file.
     */
    private static String forLog(String path) {
        String p = (path == null) ? "" : path;
        if (p.length() > 120) {
            p = p.substring(0, 120) + "...";
        }
        return p.replaceAll("[^A-Za-z0-9/_.\\-]", "?");
    }
}
