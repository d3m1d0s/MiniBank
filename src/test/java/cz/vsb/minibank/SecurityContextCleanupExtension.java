package cz.vsb.minibank;

import cz.vsb.minibank.application.auth.SecurityContext;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Clears the security context after every test in the suite.
 *
 * {@link SecurityContext} is a static ThreadLocal and surefire reuses the thread, so a user left
 * in it outlives the test that put it there. Nothing sets it more quietly than
 * {@code AuthService.login}, which does it as a side effect of returning the user: a test that
 * only means to assert a successful sign-in signs the rest of the fork in as that user, and every
 * audit line {@link cz.vsb.minibank.application.audit.AppLogger} writes afterwards is attributed to
 * them.
 *
 * Registered for the whole suite rather than per class, through
 * {@code junit-platform.properties} and the service file beside it. Five test classes used to
 * carry an {@code @AfterEach} that did nothing but this, and the one class that needed it most
 * did not have one - which is the argument for the invariant living in one place that nobody has
 * to remember.
 *
 * A test that wants an empty context to assert against still clears it itself, because that is
 * arrangement rather than cleanup and it keeps such a test readable on its own.
 */
public class SecurityContextCleanupExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        SecurityContext.clear();
    }
}
