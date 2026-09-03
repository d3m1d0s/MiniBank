package cz.vsb.minibank;

import cz.vsb.minibank.application.auth.SecurityContext;
import cz.vsb.minibank.domain.customer.User;
import cz.vsb.minibank.domain.customer.UserRole;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Proves the suite-wide cleanup is actually registered.
 *
 * The order is declared rather than relied upon. Surefire's default runOrder is filesystem and
 * JUnit's default method order is deterministic but unspecified, so the only honest way to assert
 * "the next test does not inherit this" is to say which test is next.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityContextCleanupExtensionTest {

    @Test
    @Order(1)
    void aTestMayLeaveASignedInUserBehind() {
        SecurityContext.setCurrentUser(
                new User(1, "alice", new byte[]{1}, new byte[]{2}, UserRole.CUSTOMER, 42));

        assertNotNull(SecurityContext.currentUser());
    }

    @Test
    @Order(2)
    void theTestAfterItStartsWithNobodySignedIn() {
        assertNull(SecurityContext.currentUser(),
                "The extension must have cleared the context left by the previous test");
    }
}
