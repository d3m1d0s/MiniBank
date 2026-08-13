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
}
