package cz.vsb.minibank.application.auth;

import cz.vsb.minibank.domain.customer.User;

/**
 * Thread local security context storing the currently authenticated user.
 */
public final class SecurityContext {

    private static final ThreadLocal<User> CURRENT = new ThreadLocal<>();

    private SecurityContext() { }

    /**
     * Returns the current user or null if there is no authenticated user.
     */
    public static User currentUser() {
        return CURRENT.get();
    }

    /**
     * Sets the current user or clears the context when null is provided.
     */
    public static void setCurrentUser(User user) {
        if (user == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(user);
        }
    }

    /**
     * Clears the current user from the security context.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
