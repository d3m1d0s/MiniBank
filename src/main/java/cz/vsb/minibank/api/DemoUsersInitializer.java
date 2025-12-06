package cz.vsb.minibank.api;

import cz.vsb.minibank.application.PasswordEncoder;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.repository.UserRepository;
import cz.vsb.minibank.infrastructure.Bootstrap;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class DemoUsersInitializer {

    private final Bootstrap infra;
    private final PasswordEncoder encoder;

    public DemoUsersInitializer(Bootstrap infra, PasswordEncoder encoder) {
        this.infra = infra;
        this.encoder = encoder;
    }

    @PostConstruct
    void initDemoUsers() {
        UserRepository users = infra.users;

        // CUSTOMER: alice / alice123
        if (users.findByUsername("alice").isEmpty()) {
            int uid = users.nextId();
            byte[] salt = encoder.generateSalt();
            byte[] hash = encoder.hash("alice123".toCharArray(), salt);
            User alice = new User(uid, "alice", hash, salt, UserRole.CUSTOMER, 2);
            users.save(alice);
        }

        // FRAUD_ANALYST: fraud / fraud123
        if (users.findByUsername("fraud").isEmpty()) {
            int uid = users.nextId();
            byte[] salt = encoder.generateSalt();
            byte[] hash = encoder.hash("fraud123".toCharArray(), salt);
            User fraud = new User(uid, "fraud", hash, salt, UserRole.FRAUD_ANALYST, null);
            users.save(fraud);
        }
    }
}
