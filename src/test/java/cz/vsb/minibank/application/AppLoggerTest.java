package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppLoggerTest {

    @TempDir
    Path tempDir;

    private Path logPath;
    private String previousLogFile;

    @BeforeEach
    void setUp() throws IOException {
        logPath = tempDir.resolve("minibank.log");
        // Surefire points the whole suite at target/; restore that afterwards
        previousLogFile = System.getProperty(MinibankProperties.LOG_FILE);
        System.setProperty(MinibankProperties.LOG_FILE, logPath.toString());
        Files.deleteIfExists(logPath);
        SecurityContext.clear();
    }

    @AfterEach
    void tearDown() {
        if (previousLogFile == null) {
            System.clearProperty(MinibankProperties.LOG_FILE);
        } else {
            System.setProperty(MinibankProperties.LOG_FILE, previousLogFile);
        }
        // The resolved destination is process-wide and is normally set once at startup, so a test
        // that sets it has to put it back or the rest of the suite writes wherever it pointed.
        AppLogger.useLogFile(null);
    }

    @Test
    void logsInfoWithoutCurrentUser() throws IOException {
        // when
        AppLogger.info("test.category", "Hello world");

        // then
        assertTrue(Files.exists(logPath), "minibank.log should be created");

        List<String> lines = Files.readAllLines(logPath);
        assertFalse(lines.isEmpty(), "Log should contain at least one line");

        String line = lines.get(0);
        // format: timestamp LEVEL [category] user=... | message
        assertTrue(line.contains("INFO"), "Line should contain INFO level");
        assertTrue(line.contains("[test.category]"), "Line should contain category");
        assertTrue(line.contains("user=<none>"), "Line should contain user=<none>");
        assertTrue(line.contains("Hello world"), "Line should contain original message");
    }

    @Test
    void logsWarnWithCurrentUser() throws IOException {
        // given
        User user = new User(
                1,
                "alice",
                new byte[]{1, 2, 3},
                new byte[]{4, 5, 6},
                UserRole.CUSTOMER,
                42
        );
        SecurityContext.setCurrentUser(user);

        // when
        AppLogger.warn("test.category", "Something happened");

        // then
        String log = Files.readString(logPath);
        assertTrue(log.contains("WARN"), "Log should contain WARN level");
        assertTrue(log.contains("[test.category]"), "Log should contain category");
        assertTrue(log.contains("user=alice(CUSTOMER)"),
                "Log should contain username and role from SecurityContext");
        assertTrue(log.contains("Something happened"),
                "Log should contain original message");
    }

    @Test
    void logsErrorWithStacktrace() throws IOException {
        // when
        RuntimeException ex = new RuntimeException("Boom");
        AppLogger.error("test.category", "Failure", ex);

        // then
        String log = Files.readString(logPath);
        assertTrue(log.contains("ERROR"), "Log should contain ERROR level");
        assertTrue(log.contains("Failure"), "Log should contain error message");
        assertTrue(log.contains("java.lang.RuntimeException"),
                "Log should contain exception class name");
        assertTrue(log.contains("Boom"),
                "Log should contain exception message");
    }

    /**
     * The default destination is inside the directory the project already treats as runtime
     * state, and is not a file at the root of the repository.
     *
     * Running the application in a clone used to leave minibank.log beside the pom, where the
     * only thing keeping it out of a commit was a line in .gitignore. Read off the constant
     * rather than exercised: a test that took the default would have to write the file, which is
     * exactly what this says must not happen in the working tree.
     */
    @Test
    void theDefaultDestinationIsNotTheRepositoryRoot() {
        Path directory = Paths.get(MinibankProperties.LOG_FILE_DEFAULT).getParent();

        assertNotNull(directory, "the default log destination names no directory: "
                + MinibankProperties.LOG_FILE_DEFAULT);
        assertEquals(Paths.get(MinibankProperties.JSON_PATH_DEFAULT).getParent(), directory,
                "and it is the directory the rest of the runtime state already lives in");
    }

    /**
     * A destination whose directory does not exist yet is created rather than losing the line.
     *
     * This is what the default asks for on a fresh clone: storage/ is runtime state, so nothing
     * has made it before the first line is logged, and in SQL mode no store ever will.
     */
    @Test
    void aDestinationInsideAMissingDirectoryIsStillWritten() throws IOException {
        Path inAMissingDirectory = tempDir.resolve("not-created-yet").resolve("minibank.log");
        System.setProperty(MinibankProperties.LOG_FILE, inAMissingDirectory.toString());

        AppLogger.info("test.category", "Hello from a directory that did not exist");

        assertTrue(Files.exists(inAMissingDirectory), "the log file should have been created");
        assertTrue(Files.readString(inAMissingDirectory)
                .contains("Hello from a directory that did not exist"));
    }

    @Test
    void survivesALogFileThatCannotBeAPath() {
        // The embedded NUL is what makes this value unusable: Windows rejects every control
        // character in a path and the Unix providers reject NUL in particular, so both throw
        // InvalidPathException, which is unchecked. The property is restored by tearDown.
        System.setProperty(MinibankProperties.LOG_FILE, "target\0minibank.log");

        PrintStream previousErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            assertDoesNotThrow(
                    () -> AppLogger.audit("test.category", "Transfer committed"),
                    "A misconfigured log destination must not fail the audited operation");
        } finally {
            System.setErr(previousErr);
        }

        String stderr = captured.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("Transfer committed"),
                "The line should still reach stderr when the file cannot be opened");
        assertTrue(stderr.contains("InvalidPathException"),
                "The broken configuration should be reported on stderr, not swallowed");
    }

    // -------------------------------------------------------------------------
    // Where the destination is allowed to come from
    // -------------------------------------------------------------------------

    /**
     * The REST API resolves this key the way it resolves every other one, through its own
     * environment, and hands the answer over here. That is what lets minibank.log.file be set in
     * application.properties or in a variable, which the documents have always claimed and which
     * a system property alone cannot do.
     *
     * The console entry points have no such environment, so they keep arriving by {@code -D} and
     * this stays empty for them.
     */
    @Test
    void aDestinationResolvedByTheApiIsUsedWhenNoSystemPropertyNamesOne() throws IOException {
        System.clearProperty(MinibankProperties.LOG_FILE);
        Path resolved = tempDir.resolve("from-the-environment.log");

        AppLogger.useLogFile(resolved.toString());
        AppLogger.info("test.category", "Hello from the environment");

        assertTrue(Files.exists(resolved), "the resolved destination should have been written to");
        assertTrue(Files.readString(resolved).contains("Hello from the environment"));
    }

    /**
     * And the command line still wins.
     *
     * It is the one place an operator can steer any entry point from, including the ones that
     * never read a file, so a value the API happened to read out of its own configuration must not
     * overrule it. This is the case that decides which of the two is a default and which is an
     * override.
     */
    @Test
    void theCommandLineWinsOverWhatTheApiResolved() throws IOException {
        Path fromTheEnvironment = tempDir.resolve("from-the-environment.log");
        System.setProperty(MinibankProperties.LOG_FILE, logPath.toString());

        AppLogger.useLogFile(fromTheEnvironment.toString());
        AppLogger.info("test.category", "Hello from the command line");

        assertFalse(Files.exists(fromTheEnvironment),
                "-D must not be overruled by a file one entry point happens to read");
        assertTrue(Files.readString(logPath).contains("Hello from the command line"));
    }
}
