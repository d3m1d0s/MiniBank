package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppLoggerTest {

    private static final Path LOG_PATH = Paths.get("minibank.log");

    @BeforeEach
    void setUp() throws IOException {
        // Clear the log file and SecurityContext before each test
        Files.deleteIfExists(LOG_PATH);
        SecurityContext.clear();
    }

    @Test
    void logsInfoWithoutCurrentUser() throws IOException {
        // when
        AppLogger.info("test.category", "Hello world");

        // then
        assertTrue(Files.exists(LOG_PATH), "minibank.log should be created");

        List<String> lines = Files.readAllLines(LOG_PATH);
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
        String log = Files.readString(LOG_PATH);
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
        String log = Files.readString(LOG_PATH);
        assertTrue(log.contains("ERROR"), "Log should contain ERROR level");
        assertTrue(log.contains("Failure"), "Log should contain error message");
        assertTrue(log.contains("java.lang.RuntimeException"),
                "Log should contain exception class name");
        assertTrue(log.contains("Boom"),
                "Log should contain exception message");
    }
}
