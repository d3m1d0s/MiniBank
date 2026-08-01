package cz.vsb.minibank.api;

import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.exceptions.AccessDeniedException;
import cz.vsb.minibank.domain.exceptions.NotAuthenticatedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthHelpersTest {

    private static User customerUser() {
        return new User(
                1,
                "alice",
                new byte[]{1},
                new byte[]{2},
                UserRole.CUSTOMER,
                100
        );
    }

    private static User fraudUser() {
        return new User(
                2,
                "fraud",
                new byte[]{3},
                new byte[]{4},
                UserRole.FRAUD_ANALYST,
                null
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void requireUserThrowsWhenNotLoggedIn() {
        SecurityContext.clear();
        assertThrows(NotAuthenticatedException.class, AuthHelpers::requireUser);
    }

    @Test
    void requireCustomerIdReturnsIdForCustomer() {
        SecurityContext.setCurrentUser(customerUser());

        int cid = AuthHelpers.requireCustomerId();
        assertEquals(100, cid);
    }

    @Test
    void requireCustomerIdThrowsForNonCustomer() {
        SecurityContext.setCurrentUser(fraudUser());

        assertThrows(AccessDeniedException.class, AuthHelpers::requireCustomerId);
    }

    @Test
    void requireRoleAllowsMatchingRole() {
        SecurityContext.setCurrentUser(fraudUser());

        assertDoesNotThrow(() -> AuthHelpers.requireRole(UserRole.FRAUD_ANALYST));
    }

    @Test
    void requireRoleThrowsForWrongRole() {
        SecurityContext.setCurrentUser(customerUser());

        assertThrows(AccessDeniedException.class,
                () -> AuthHelpers.requireRole(UserRole.FRAUD_ANALYST));
    }
}
