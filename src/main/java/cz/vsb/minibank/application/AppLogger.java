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
 * Simple application wide logger that writes to stderr and to a text file, storage/minibank.log
 * unless {@link MinibankProperties#LOG_FILE} names another one.
 */
public final class AppLogger {

    /**
     * The destination an entry point resolved for itself, used when no system property names one.
     *
     * Only the REST API sets it, and only because it can read the key from places a system
     * property cannot come from: its application.properties and the environment. Everything else
     * here, the console entry points and the test harness included, arrives by {@code -D}.
     */
    private static volatile String configuredLogFile;

    private AppLogger() {
    }

    /**
     * Points the log at {@code path}, or back at the system property and the default when it is
     * null. Called once, at startup, before anything worth logging has happened.
     */
    public static void useLogFile(String path) {
        configuredLogFile = path;
    }

    /**
     * Resolves the log file on every write so the destination can be redirected
     * at runtime, which also keeps tests off the working directory.
     *
     * The system property first, because that is the one an operator can put on the command line
     * of any entry point, and it must not be overruled by a file one of them happens to read.
     */
    private static Path logFile() {
        String fromCommandLine = System.getProperty(MinibankProperties.LOG_FILE);
        if (fromCommandLine != null) {
            return Paths.get(fromCommandLine);
        }
        String configured = configuredLogFile;
        return Paths.get(configured != null ? configured : MinibankProperties.LOG_FILE_DEFAULT);
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
        try {
            // The destination is resolved inside the try, not above it: the configured value can
            // be something that is not a path at all, and Paths.get then throws the unchecked
            // InvalidPathException that the catch below is half written for.
            Path destination = logFile();
            // The default destination lives in a directory that is runtime state and so is absent
            // from a fresh clone. Created here rather than at startup because nothing owns the
            // moment before the first line: the logger is reached from entry points that build no
            // store of their own.
            Path directory = destination.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            try (BufferedWriter out = Files.newBufferedWriter(
                    destination,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            )) {
                out.write(base);
                if (t != null) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    out.write(sw.toString());
                }
            }
        } catch (IOException | RuntimeException e) {
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
