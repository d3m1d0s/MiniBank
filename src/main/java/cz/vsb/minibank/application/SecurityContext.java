package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.User;

public final class SecurityContext {

    private static final ThreadLocal<User> CURRENT = new ThreadLocal<>();

    private SecurityContext() { }

    public static User currentUser() {
        return CURRENT.get();
    }

    public static void setCurrentUser(User user) {
        if (user == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(user);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
