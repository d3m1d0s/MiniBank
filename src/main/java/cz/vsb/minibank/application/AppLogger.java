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

    private AppLogger() {
    }

    /**
     * Resolves the log file on every write so the destination can be redirected
     * at runtime, which also keeps tests off the working directory.
     */
    private static Path logFile() {
        return Paths.get(System.getProperty(
                MinibankProperties.LOG_FILE, MinibankProperties.LOG_FILE_DEFAULT));
    }

    /**
     * Writes one line to stderr and appends it to the log file. Nothing short of an Error
     * gets out of this call.
     * <p>
     * The destination is a runtime setting, so it can hold a value that is not a path at all,
     * and the callers that make that expensive are the audit observers, which run once the
     * unit of work has committed, and the REST exception handler, which is already busy
     * turning one failure into a response. A throw from here would report a second failure
     * for work the database has accepted and charged the customer for. A logger reports on
     * an operation and must not be able to fail it, so a broken destination costs the log
     * file and nothing else.
     */
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
                logFile(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        )) {
            out.write(base);
            if (t != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                out.write(sw.toString());
            }
        } catch (IOException | RuntimeException e) {
            // The unchecked half is what covers resolving the destination: logFile() is evaluated
            // in the resource specification, which the language nests inside this try, and it
            // throws InvalidPathException when the configured value cannot be a path at all.
            // Hoisting that call above the try would put it back outside the catch.
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
