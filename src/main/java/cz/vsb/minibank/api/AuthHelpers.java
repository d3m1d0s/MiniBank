package cz.vsb.minibank.api;

import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.exceptions.AccessDeniedException;
import cz.vsb.minibank.domain.exceptions.NotAuthenticatedException;

/**
 * Helper methods for accessing the authenticated user and enforcing authorization rules.
 */
public final class AuthHelpers {

    private AuthHelpers() {}

    /**
     * Returns the current authenticated user or throws when no user is authenticated.
     *
     * @return authenticated user
     * @throws NotAuthenticatedException if there is no authenticated user
     */
    public static User requireUser() {
        User u = SecurityContext.currentUser();
        if (u == null) {
            throw new NotAuthenticatedException("No authenticated user in the security context");
        }
        return u;
    }

    /**
     * Returns the customer identifier of the current user or throws when the user is not a customer.
     *
     * @return customer identifier
     * @throws AccessDeniedException if the user is not a customer
     */
    public static int requireCustomerId() {
        User u = requireUser();
        if (!u.isCustomer()) {
            throw AccessDeniedException.forRole("User " + u.username() + " is not a customer");
        }
        return u.customerId();
    }

    /**
     * Ensures that the current user has the required role.
     *
     * @param role role that must be present
     * @throws AccessDeniedException if the current user does not have the role
     */
    public static void requireRole(UserRole role) {
        User u = requireUser();
        if (!u.hasRole(role)) {
            throw AccessDeniedException.forRole(
                    "Required role " + role + ", user " + u.username() + " has " + u.role());
        }
    }
}
