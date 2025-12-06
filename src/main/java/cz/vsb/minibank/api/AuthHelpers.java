package cz.vsb.minibank.api;

import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.exceptions.AuthorizationFailedException;

public final class AuthHelpers {

    private AuthHelpers() {}

    public static User requireUser() {
        User u = SecurityContext.currentUser();
        if (u == null) {
            throw new AuthorizationFailedException("User is not authenticated");
        }
        return u;
    }

    public static int requireCustomerId() {
        User u = requireUser();
        if (!u.isCustomer()) {
            throw new AuthorizationFailedException("Current user is not a customer");
        }
        return u.customerId();
    }

    public static void requireRole(UserRole role) {
        User u = requireUser();
        if (!u.hasRole(role)) {
            throw new AuthorizationFailedException("Access denied for role " + u.role());
        }
    }
}
