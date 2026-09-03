package cz.vsb.minibank.api.config;

import cz.vsb.minibank.application.audit.AppLogger;
import cz.vsb.minibank.application.auth.PasswordEncoder;
import cz.vsb.minibank.demo.DemoScenario;
import cz.vsb.minibank.domain.customer.User;
import cz.vsb.minibank.domain.customer.UserRole;
import cz.vsb.minibank.domain.repository.UserRepository;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Prepares the demo dataset for the REST API: seeds the business data through
 * {@link DemoScenario} and binds the demo logins to the customer it produced.
 * <p>
 * The credentials it creates are throwaway values that exist so the project can
 * be tried without setup. They are not secrets and must not be reused. Guarded
 * by the {@code demo} profile, which is active by default.
 * <p>
 * Constructing this bean writes nothing. {@link StartupSequence} calls
 * {@link #seedDemoData()} once the context is ready, so a database that refuses
 * the first write is reported as a seed that failed rather than as a bean that
 * could not be built.
 */
@Component
@Profile(MinibankApiConfig.DEMO_PROFILE)
public class DemoUsersInitializer {

    private static final String DEMO_CUSTOMER_LOGIN = "alice";
    private static final String DEMO_CUSTOMER_PASSWORD = "alice123";
    private static final String DEMO_ANALYST_LOGIN = "fraud";
    private static final String DEMO_ANALYST_PASSWORD = "fraud123";

    private final Bootstrap infra;
    private final PasswordEncoder encoder;
    private final DemoScenario scenario;

    public DemoUsersInitializer(Bootstrap infra, PasswordEncoder encoder, DemoScenario scenario) {
        this.infra = infra;
        this.encoder = encoder;
        this.scenario = scenario;
    }

    void seedDemoData() {
        int customerId = scenario.seed();
        ensureDemoUsers(customerId);
        AppLogger.warn("api", "Demo profile is active: logins "
                + DEMO_CUSTOMER_LOGIN + "/" + DEMO_CUSTOMER_PASSWORD + " and "
                + DEMO_ANALYST_LOGIN + "/" + DEMO_ANALYST_PASSWORD
                + " are available and the one time password is a fixed constant. "
                + "Start with a different profile to disable this.");
    }

    /**
     * Creates the demo customer and fraud analyst logins if they are missing.
     * Runs in its own unit of work because the scenario has already committed.
     */
    private void ensureDemoUsers(int customerId) {
        UserRepository users = infra.users;

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            if (users.findByUsername(DEMO_CUSTOMER_LOGIN).isEmpty()) {
                int uid = users.nextId();
                byte[] salt = encoder.generateSalt();
                byte[] hash = encoder.hash(DEMO_CUSTOMER_PASSWORD.toCharArray(), salt);
                users.save(new User(uid, DEMO_CUSTOMER_LOGIN, hash, salt, UserRole.CUSTOMER, customerId));
            }

            if (users.findByUsername(DEMO_ANALYST_LOGIN).isEmpty()) {
                int uid = users.nextId();
                byte[] salt = encoder.generateSalt();
                byte[] hash = encoder.hash(DEMO_ANALYST_PASSWORD.toCharArray(), salt);
                users.save(new User(uid, DEMO_ANALYST_LOGIN, hash, salt, UserRole.FRAUD_ANALYST, null));
            }

            scope.uow().commit();
        }
    }
}
