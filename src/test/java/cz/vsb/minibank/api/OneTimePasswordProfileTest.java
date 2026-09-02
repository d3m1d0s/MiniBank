package cz.vsb.minibank.api;

import cz.vsb.minibank.application.FixedOtpValidator;
import cz.vsb.minibank.application.MinibankProperties;
import cz.vsb.minibank.application.OtpValidator;
import cz.vsb.minibank.application.RefusingOtpValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which one time password validator a run gets, decided by the profile and not by a constructor.
 * <p>
 * {@link FixedOtpValidator} accepts two compile-time constants and verifies nothing. It used to be
 * built inside {@code BootstrapServices}, with no profile anywhere near it, which meant every run
 * of the API authorized payments against a published code whatever it was started with. What keeps
 * it honest now is that reaching it takes the profile that says out loud it is a demonstration.
 * <p>
 * A real context rather than a reading of the annotations: the annotation is not the behaviour,
 * the bean the context hands out is, and the two have differed before. The context is built on the
 * JSON store in a temporary directory, so it opens no database and leaves nothing behind.
 */
class OneTimePasswordProfileTest {

    @TempDir
    Path tempDir;

    private AnnotationConfigApplicationContext contextWith(String... profiles) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "the store this test may write to",
                Map.of(MinibankProperties.STORAGE, "json",
                        MinibankProperties.JSON_PATH, tempDir.resolve("data.json").toString())));
        context.register(MinibankApiConfig.class);
        context.refresh();
        return context;
    }

    /**
     * The run the README describes for anyone else: no demo profile, and the constant is refused.
     *
     * Refused rather than absent, because an absent validator would fail the context and take the
     * whole API down over a step most of it does not use. What this run cannot do is authorize a
     * payment, and that is a true statement about a bank with no provider wired up.
     */
    @Test
    void withoutTheDemoProfileTheFixedCodeIsRefused() {
        try (AnnotationConfigApplicationContext context = contextWith("plain")) {
            OtpValidator otp = context.getBean(OtpValidator.class);

            assertInstanceOf(RefusingOtpValidator.class, otp);
            assertFalse(otp.isValid(1, FixedOtpValidator.DEMO_OTP),
                    "the published constant must not authorize a payment outside the demo");
            assertFalse(otp.isValid(1, FixedOtpValidator.DEMO_OTP_ALTERNATE));
            assertFalse(otp.isValid(1, "742913"),
                    "and neither must anything else: there is no provider to have issued it");
        }
    }

    /** And under the profile that admits to being a demonstration, the constant works. */
    @Test
    void underTheDemoProfileTheFixedCodeIsAccepted() {
        try (AnnotationConfigApplicationContext context = contextWith(MinibankApiConfig.DEMO_PROFILE)) {
            OtpValidator otp = context.getBean(OtpValidator.class);

            assertInstanceOf(FixedOtpValidator.class, otp);
            assertTrue(otp.isValid(1, FixedOtpValidator.DEMO_OTP));
        }
    }

    /**
     * One validator either way, so nothing has to guess which of two the services were built from.
     *
     * The pair of bean methods is written as a profile and its negation, and a mistake there is
     * not a compile error: two beans would fail the injection into the services bean, and none
     * would fail the context, so both accidents are asserted against here rather than left to
     * whichever of them happens to break first.
     */
    @Test
    void exactlyOneValidatorIsDefinedUnderEitherProfile() {
        try (AnnotationConfigApplicationContext plain = contextWith("plain");
             AnnotationConfigApplicationContext demo = contextWith(MinibankApiConfig.DEMO_PROFILE)) {

            assertEquals(1, plain.getBeanNamesForType(OtpValidator.class).length);
            assertEquals(1, demo.getBeanNamesForType(OtpValidator.class).length);
        }
    }

    /**
     * The seeding bean is under the same profile, so a run without it neither creates the demo
     * logins nor accepts the code that goes with them. The two used to be able to disagree: the
     * logins were behind the profile and the code was not.
     */
    @Test
    void aRunThatCreatesNoDemoLoginsAlsoAcceptsNoDemoCode() {
        try (AnnotationConfigApplicationContext context = contextWith("plain")) {
            assertEquals(0, context.getBeanNamesForType(DemoUsersInitializer.class).length,
                    "the demo logins are not created outside the demo profile");
            assertInstanceOf(RefusingOtpValidator.class, context.getBean(OtpValidator.class));
        }
    }
}
