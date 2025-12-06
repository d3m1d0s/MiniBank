package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.User;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Simple application wide logger that writes to stderr and a text file minibank.log.
 */
public final class AppLogger {

    private static final Path LOG_FILE = Paths.get("minibank.log");

    private AppLogger() {
    }

    public static void log(LogLevel level, String category, String message, Throwable t) {
        Instant now = Instant.now();
        User user = SecurityContext.currentUser();

        String userPart;
        if (user != null) {
            userPart = "user=" + user.username() + "(" + user.role() + ")";
        } else {
            userPart = "user=<none>";
        }

        String base = String.format(
                "%s %-5s [%s] %s | %s%n",
                now,
                level,
                category,
                userPart,
                message
        );

        // 1) stderr - do not mix logs with regular menu output
        System.err.print(base);

        // 2) log file
        try (BufferedWriter out = Files.newBufferedWriter(
                LOG_FILE,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        )) {
            out.write(base);
            if (t != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                out.write(sw.toString());
            }
        } catch (IOException e) {
            // Logging failed as well - just print the stack trace to stderr
            e.printStackTrace();
        }
    }

    public static void info(String category, String message) {
        log(LogLevel.INFO, category, message, null);
    }

    public static void warn(String category, String message) {
        log(LogLevel.WARN, category, message, null);
    }

    public static void warn(String category, String message, Throwable t) {
        log(LogLevel.WARN, category, message, t);
    }

    public static void error(String category, String message, Throwable t) {
        log(LogLevel.ERROR, category, message, t);
    }

    public static void audit(String category, String message) {
        log(LogLevel.AUDIT, category, message, null);
    }
}
