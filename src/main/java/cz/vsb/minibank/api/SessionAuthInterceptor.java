package cz.vsb.minibank.api;

import cz.vsb.minibank.api.ApiError;
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
            writeUnauthorized(response, "Missing session id");
            return false;
        }


        User user = sessions.findUser(sessionId)
                .orElse(null);

        if (user == null) {
            writeUnauthorized(response, "Invalid session id");
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

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");

        ApiError body = new ApiError("AUTH_REQUIRED", message);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
