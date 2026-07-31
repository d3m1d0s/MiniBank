package cz.vsb.minibank.api;

import cz.vsb.minibank.application.PasswordEncoder;
import cz.vsb.minibank.demo.DemoScenario;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.repository.UserRepository;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Prepares the demo dataset for the REST API: seeds the business data through
 * {@link DemoScenario} and binds the demo logins to the customer it produced.
 * <p>
 * The scenario is injected rather than looked up so that Spring is forced to
 * build it before this bean's {@code @PostConstruct} runs.
 */
@Component
public class DemoUsersInitializer {

    private final Bootstrap infra;
    private final PasswordEncoder encoder;
    private final DemoScenario scenario;

    public DemoUsersInitializer(Bootstrap infra, PasswordEncoder encoder, DemoScenario scenario) {
        this.infra = infra;
        this.encoder = encoder;
        this.scenario = scenario;
    }

    @PostConstruct
    void initDemoData() {
        int customerId = scenario.seed();
        ensureDemoUsers(customerId);
    }

    /**
     * Creates the demo customer and fraud analyst logins if they are missing.
     * Runs in its own unit of work because the scenario has already committed.
     */
    private void ensureDemoUsers(int customerId) {
        UserRepository users = infra.users;

        UnitOfWork uow = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            if (users.findByUsername("alice").isEmpty()) {
                int uid = users.nextId();
                byte[] salt = encoder.generateSalt();
                byte[] hash = encoder.hash("alice123".toCharArray(), salt);
                users.save(new User(uid, "alice", hash, salt, UserRole.CUSTOMER, customerId));
            }

            if (users.findByUsername("fraud").isEmpty()) {
                int uid = users.nextId();
                byte[] salt = encoder.generateSalt();
                byte[] hash = encoder.hash("fraud123".toCharArray(), salt);
                users.save(new User(uid, "fraud", hash, salt, UserRole.FRAUD_ANALYST, null));
            }

            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }
}
