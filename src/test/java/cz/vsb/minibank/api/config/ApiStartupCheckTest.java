package cz.vsb.minibank.api.config;

import cz.vsb.minibank.application.audit.AppLogger;
import cz.vsb.minibank.application.config.MinibankProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the API says at startup, and what it says instead of a stack trace when it cannot start.
 * <p>
 * The whole decision is {@link ApiStartupCheck#inspect}, which takes an environment and something
 * to ask about the database and returns text. That is the only reason these cases exist: the
 * behaviour they pin used to be a Spring bean failing, which can be reproduced only by starting an
 * application against a machine with no PostgreSQL on it. Here the absent server is a lambda.
 * <p>
 * The claims are about the text, because the text is the whole feature. An operator whose database
 * is not running gets one thing out of this program, and it is either an address and a command or
 * it is forty lines of frames.
 */
class ApiStartupCheckTest {

    /** A database that answers. */
    private static final ApiStartupCheck.DatabaseProbe ANSWERS = (url, user, password) -> Optional.empty();

    /** And one that does not, in the driver's own words. */
    private static final ApiStartupCheck.DatabaseProbe SILENT = (url, user, password) ->
            Optional.of("Connection to localhost:55432 refused.");

    private static MockEnvironment environment(String... keysAndValues) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            environment.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return environment;
    }

    // -------------------------------------------------------------------------
    // The one line at start
    // -------------------------------------------------------------------------

    /**
     * The line an operator reads to know which of the two stores this process opened and where.
     *
     * Both halves are asserted because either alone has been ambiguous in practice: the backend
     * without the address does not say which database, and the address without the backend reads
     * like a url that was configured rather than one that was used.
     */
    @Test
    void aDatabaseThatAnsweredIsOneLineNamingTheBackendAndTheAddress() {
        ApiStartupCheck.StartupReport report = ApiStartupCheck.inspect(
                environment(MinibankProperties.STORAGE, "sql",
                        MinibankProperties.SQL_URL, "jdbc:postgresql://localhost:55432/minibank",
                        MinibankProperties.SQL_USER, "minibank"),
                ANSWERS);

        assertNull(report.refusal(), "a database that answers must not stop the start");
        assertNotNull(report.line());
        assertTrue(report.line().contains("PostgreSQL"), report.line());
        assertTrue(report.line().contains("jdbc:postgresql://localhost:55432/minibank"), report.line());
        assertTrue(report.line().contains("minibank"), report.line());
        assertEquals(1, report.line().lines().count(), "one line, and it is the first thing printed");
    }

    /**
     * The JSON store says where it landed, not what was configured.
     *
     * The configured value is relative, so what it means depends on the directory the process was
     * started from, and that is precisely the part an operator cannot see from the outside.
     */
    @Test
    void theJsonStoreNamesTheFileItResolvedTo() {
        ApiStartupCheck.StartupReport report = ApiStartupCheck.inspect(
                environment(MinibankProperties.STORAGE, "json",
                        MinibankProperties.JSON_PATH, "storage/data.json"),
                ANSWERS);

        assertNull(report.refusal());
        assertTrue(report.line().contains(Paths.get("storage/data.json").toAbsolutePath().toString()),
                report.line());
    }

    /** And it asks no database anything, since in this mode there is none to ask. */
    @Test
    void theJsonStoreNeverProbesADatabase() {
        AtomicInteger probes = new AtomicInteger();

        ApiStartupCheck.inspect(
                environment(MinibankProperties.STORAGE, "json"),
                (url, user, password) -> {
                    probes.incrementAndGet();
                    return Optional.empty();
                });

        assertEquals(0, probes.get(), "a run without a database must not wait for one");
    }

    // -------------------------------------------------------------------------
    // Starting with no database
    // -------------------------------------------------------------------------

    /**
     * The refusal, which is the whole of C3: an address, the key that steers it, and a command.
     *
     * What it replaces was a Spring context failure wrapping a PSQLException, where the address
     * appeared once inside a frame and the thing to do about it appeared nowhere.
     */
    @Test
    void aDatabaseThatDoesNotAnswerIsAnAddressAndACommand() {
        ApiStartupCheck.StartupReport report = ApiStartupCheck.inspect(
                environment(MinibankProperties.STORAGE, "sql",
                        MinibankProperties.SQL_URL, "jdbc:postgresql://localhost:55432/minibank",
                        MinibankProperties.SQL_USER, "minibank"),
                SILENT);

        assertNull(report.line(), "a start that is refused has nothing to announce");
        String refusal = report.refusal();
        assertNotNull(refusal);
        assertTrue(refusal.contains("jdbc:postgresql://localhost:55432/minibank"),
                "the address it tried: " + refusal);
        assertTrue(refusal.contains(MinibankProperties.SQL_URL),
                "the key that points it there: " + refusal);
        assertTrue(refusal.contains("docker compose up -d"),
                "and what to run: " + refusal);
        assertTrue(refusal.contains("Connection to localhost:55432 refused."),
                "with the driver's own reason: " + refusal);
    }

    /**
     * And the value {@code minibank.storage} really holds, not the word the code sorted it into.
     *
     * Three spellings reach the SQL branch. Telling an operator their key says sql when they typed
     * postgres sends them looking for a second configuration file.
     */
    @Test
    void theRefusalQuotesTheStorageValueThatWasActuallyConfigured() {
        String refusal = ApiStartupCheck.inspect(
                environment(MinibankProperties.STORAGE, "postgres"), SILENT).refusal();

        assertTrue(refusal.contains("'postgres'"), refusal);
    }

    /**
     * And no stack trace in it.
     *
     * Asserted on the text rather than trusted to the absence of a throw, because the text is what
     * the operator sees; a frame that reached this block would be indistinguishable from the
     * failure this replaced.
     */
    @Test
    void theRefusalCarriesNoStackTrace() {
        String refusal = ApiStartupCheck.inspect(
                environment(MinibankProperties.STORAGE, "sql"), SILENT).refusal();

        assertFalse(refusal.contains("\tat "), refusal);
        assertFalse(refusal.contains("cz.vsb.minibank"), refusal);
        assertFalse(refusal.contains("Exception"), refusal);
    }

    /**
     * A reason several failures deep is cut to its first line, which is the sentence worth
     * repeating. The rest is the same failure wrapped again.
     */
    @Test
    void onlyTheFirstLineOfTheReasonIsRepeated() {
        String refusal = ApiStartupCheck.inspect(
                environment(MinibankProperties.STORAGE, "sql"),
                (url, user, password) -> Optional.of(ApiStartupCheck.firstLine(
                        "Connection refused.\n\tat org.postgresql.Driver.connect(Driver.java:1)")))
                .refusal();

        assertTrue(refusal.contains("Connection refused."), refusal);
        assertFalse(refusal.contains("Driver.java"), refusal);
    }

    /**
     * A configuration that names nothing but the backend is refused with the address it tried
     * and with one command that makes that address answer.
     *
     * The default and the port docker-compose.yml publishes are the same address now, so the
     * command is all there is to do about it. While they disagreed, the refusal had to quote the
     * published port on top of the default and ask for it on the command line, and this test read
     * the two apart; that this now asserts one address rather than two is the repair, not a
     * weakening.
     */
    @Test
    void theRefusalNamesTheAddressItTriedAndTheCommandThatAnswersIt() {
        String refusal = ApiStartupCheck.inspect(
                environment(MinibankProperties.STORAGE, "sql"), SILENT).refusal();

        assertTrue(refusal.contains(MinibankProperties.SQL_URL_DEFAULT),
                "the address it actually tried is the default here: " + refusal);
        assertTrue(refusal.contains("55432"),
                "and that default is the port docker compose publishes: " + refusal);
        assertTrue(refusal.contains("docker compose up -d"),
                "with nothing left to add to the command: " + refusal);
        assertFalse(refusal.contains("-D" + MinibankProperties.SQL_URL + "="
                        + MinibankProperties.SQL_URL_DEFAULT),
                "so it must not ask for a url that is already the default: " + refusal);
    }

    /**
     * A password carried in the url's parameters is not printed.
     *
     * Everything in this block goes to a console and to a log file, both of which outlive the
     * process, and the url is the one configured value that can hold a credential.
     */
    @Test
    void aCredentialInTheUrlParametersIsNotPrinted() {
        String withPassword = "jdbc:postgresql://localhost:55432/minibank?user=minibank&password=hunter2";

        String refusal = ApiStartupCheck.inspect(
                environment(MinibankProperties.STORAGE, "sql", MinibankProperties.SQL_URL, withPassword),
                SILENT).refusal();

        assertFalse(refusal.contains("hunter2"), refusal);
        assertTrue(refusal.contains("jdbc:postgresql://localhost:55432/minibank"), refusal);
        assertTrue(refusal.contains("parameters omitted"), refusal);
    }

    /**
     * A storage value that names neither store is refused here rather than several beans later,
     * and without asking any database anything: there is no store to open, so there is nothing to
     * wait three seconds for.
     */
    @Test
    void anUnreadableStorageValueIsRefusedWithoutProbingAnything() {
        AtomicInteger probes = new AtomicInteger();

        ApiStartupCheck.StartupReport report = ApiStartupCheck.inspect(
                environment(MinibankProperties.STORAGE, "postgre"),
                (url, user, password) -> {
                    probes.incrementAndGet();
                    return Optional.empty();
                });

        assertEquals(0, probes.get());
        assertNull(report.line());
        assertTrue(report.refusal().contains("postgre"), report.refusal());
        assertTrue(report.refusal().contains(MinibankProperties.STORAGE), report.refusal());
    }

    // -------------------------------------------------------------------------
    // The vocabulary, shared with the configuration that builds the store
    // -------------------------------------------------------------------------

    /**
     * Both spellings this check calls SQL are spellings {@code MinibankApiConfig.bootstrap} opens
     * a database for, because both read the same two methods.
     *
     * A list kept twice is the failure being avoided: a value this class called unknown and the
     * configuration called SQL would refuse a start that would have worked, and the operator would
     * be told to choose between two words they had already chosen one of.
     */
    @Test
    void everyWordForSqlIsOneWordForSql() {
        for (String spelling : List.of("sql", "SQL", "postgres", "Postgres", "postgresql", "POSTGRESQL")) {
            assertTrue(ApiStartupCheck.namesSql(spelling), spelling);
            assertFalse(ApiStartupCheck.namesJson(spelling), spelling);
        }
        for (String spelling : List.of("json", "JSON", "Json")) {
            assertTrue(ApiStartupCheck.namesJson(spelling), spelling);
            assertFalse(ApiStartupCheck.namesSql(spelling), spelling);
        }
    }

    // -------------------------------------------------------------------------
    // A key that has been renamed
    // -------------------------------------------------------------------------

    /**
     * The API cannot refuse an old key name the way the console does, because it resolves these
     * keys through placeholders that never see one. So it says so, and says which value is being
     * used instead, which is the question an old name actually raises.
     */
    @Test
    void anOldKeyNameIsNamedAndSoIsTheValueInUse() {
        MockEnvironment environment = environment(
                MinibankProperties.STORAGE, "sql",
                "minibank.jdbcUrl", "jdbc:postgresql://elsewhere:5432/minibank",
                MinibankProperties.SQL_URL, "jdbc:postgresql://localhost:55432/minibank");

        List<String> warnings = ApiStartupCheck.inspect(environment, ANSWERS).warnings();

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("minibank.jdbcUrl"), warnings.get(0));
        assertTrue(warnings.get(0).contains(MinibankProperties.SQL_URL), warnings.get(0));
        assertTrue(warnings.get(0).contains("jdbc:postgresql://localhost:55432/minibank"),
                "the value in use answers the question the old name raises: " + warnings.get(0));
    }

    /** Every renamed key is recognised, not only the url, and they come out in a fixed order. */
    @Test
    void everyRenamedKeyIsRecognised() {
        MockEnvironment environment = new MockEnvironment();
        MinibankProperties.RENAMED_KEYS.keySet().forEach(legacy -> environment.setProperty(legacy, "x"));

        List<String> warnings = ApiStartupCheck.renamedKeyWarnings(environment);

        assertEquals(MinibankProperties.RENAMED_KEYS.size(), warnings.size(), warnings.toString());
        List<String> legacyNames = List.copyOf(MinibankProperties.RENAMED_KEYS.keySet());
        for (int i = 0; i < warnings.size(); i++) {
            assertTrue(warnings.get(i).contains(legacyNames.get(i)), warnings.get(i));
        }
    }

    /**
     * And the password's value is withheld while the other two are quoted.
     *
     * The warning exists to say which value is in use, but for this one key naming it would put a
     * credential in the log to answer a question about a key name.
     */
    @Test
    void theRenamedPasswordIsNamedWithoutQuotingIt() {
        MockEnvironment environment = environment(
                MinibankProperties.STORAGE, "sql",
                "minibank.dbPass", "old",
                MinibankProperties.SQL_PASSWORD, "hunter2");

        List<String> warnings = ApiStartupCheck.inspect(environment, ANSWERS).warnings();

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains(MinibankProperties.SQL_PASSWORD), warnings.get(0));
        assertFalse(warnings.get(0).contains("hunter2"), warnings.get(0));
    }

    /** A configuration with no old names in it says nothing about them. */
    @Test
    void currentKeyNamesAreNotWarnedAbout() {
        List<String> warnings = ApiStartupCheck.inspect(
                environment(MinibankProperties.STORAGE, "sql",
                        MinibankProperties.SQL_URL, "jdbc:postgresql://localhost:55432/minibank"),
                ANSWERS).warnings();

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    /**
     * A warning is survivable, and stays survivable next to a refusal: both are reported, because
     * the old key name may well be why the address is wrong.
     */
    @Test
    void aWarningIsCarriedAlongsideARefusal() {
        ApiStartupCheck.StartupReport report = ApiStartupCheck.inspect(
                environment(MinibankProperties.STORAGE, "sql", "minibank.jdbcUrl", "x"),
                SILENT);

        assertEquals(1, report.warnings().size(), report.warnings().toString());
        assertNotNull(report.refusal());
    }

    // -------------------------------------------------------------------------
    // The log destination, handed over
    // -------------------------------------------------------------------------

    /**
     * {@link MinibankProperties#LOG_FILE} is the one key {@code AppLogger} cannot resolve for
     * itself, because it is reached from entry points that have no Spring environment to ask. The
     * API resolves it here and hands the value over, which is what makes the key behave on this
     * path the way the documents describe it: from application.properties, from the command line
     * or from a variable.
     *
     * This runs the listener rather than {@link ApiStartupCheck#inspect}, since the handover is
     * the listener's own work and would otherwise have no test at all.
     *
     * @param tempDir a destination this test may write to, so the assertion is about a real file
     */
    @Test
    void theLogDestinationTheEnvironmentCarriesIsHandedToTheLogger(@TempDir Path tempDir)
            throws IOException {
        Path destination = tempDir.resolve("api.log");
        String previous = System.getProperty(MinibankProperties.LOG_FILE);
        System.clearProperty(MinibankProperties.LOG_FILE);
        try {
            MockEnvironment environment = environment(
                    MinibankProperties.STORAGE, "json",
                    MinibankProperties.LOG_FILE, destination.toString());

            new ApiStartupCheck().onApplicationEvent(new ApplicationEnvironmentPreparedEvent(
                    new DefaultBootstrapContext(), new SpringApplication(), new String[0], environment));

            assertTrue(Files.exists(destination),
                    "the startup line should have gone to the destination the environment named");
            assertTrue(Files.readString(destination).contains("JSON store"),
                    "and it should be the line that names the backend");
        } finally {
            AppLogger.useLogFile(null);
            if (previous == null) {
                System.clearProperty(MinibankProperties.LOG_FILE);
            } else {
                System.setProperty(MinibankProperties.LOG_FILE, previous);
            }
        }
    }
}
