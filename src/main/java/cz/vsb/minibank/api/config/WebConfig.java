package cz.vsb.minibank.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import cz.vsb.minibank.api.web.SessionAuthInterceptor;

/**
 * Web MVC configuration: the session authentication interceptor, and the one cross-origin rule.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SessionAuthInterceptor sessionAuthInterceptor;

    public WebConfig(SessionAuthInterceptor sessionAuthInterceptor) {
        this.sessionAuthInterceptor = sessionAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sessionAuthInterceptor)
                .addPathPatterns("/api/**");
    }

    /**
     * The cross-origin rule, in one place instead of four.
     *
     * It used to be a {@code @CrossOrigin} annotation repeated verbatim on every controller,
     * which meant a fifth controller was one forgotten line away from being unreachable from a
     * browser, and that the allowlist could drift between them without anything noticing. It sits
     * beside the interceptor because both answer the same question: what is allowed to reach
     * {@code /api/**}.
     *
     * The two origins are the vite dev servers, 5173 for the customer app and 5174 for the
     * analyst one. Neither is needed once an app is served from the same origin as the API,
     * which is what both now do in development through their own proxies - this is what keeps
     * working if someone runs a build against a backend on another port.
     *
     * The values are not the registry's defaults and each is deliberate. Methods must be listed:
     * the default is GET, HEAD and POST, so a route added with any other verb would fail
     * preflight while the annotation, which derived its methods from the handler, would not have.
     * Headers must be opened because every authenticated request carries {@code X-Session-Id},
     * and credentials stay off because this API authenticates with that header rather than with
     * cookies.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://localhost:5174")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}
