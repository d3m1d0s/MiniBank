package cz.vsb.minibank.api;

import cz.vsb.minibank.application.AppLogger;
import cz.vsb.minibank.application.MinibankProperties;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the API says about its configuration before it builds anything, and the one place a
 * database that does not answer stops the start.
 * <p>
 * It runs on {@code ApplicationEnvironmentPreparedEvent}, which is the first moment the
 * configuration is fully resolved - application.properties, the command line and the environment
 * together - and still before any bean exists. That is what lets a missing database be reported
 * as an address and a command instead of as a bean that could not be constructed: nothing has
 * been constructed yet. Registered from {@link cz.vsb.minibank.ApiApplication}, not as a bean,
 * for the same reason.
 * <p>
 * The decision is taken by {@link #inspect}, which reads an {@link Environment} and a
 * {@link DatabaseProbe} and returns text. Only the listener below writes anything or stops the
 * process, so everything this class decides can be read back in a test without a database and
 * without a JVM that exits.
 */
public final class ApiStartupCheck
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    /**
     * How long to wait for the server before calling it absent. Short on purpose: this is the
     * check that stands between the operator and a message, so it must not itself look like a
     * hang.
     */
    private static final int PROBE_TIMEOUT_SECONDS = 3;

    /**
     * Opens a connection and says what went wrong, so {@link #inspect} can be exercised without
     * a database.
     */
    @FunctionalInterface
    interface DatabaseProbe {
        /** Empty when the server answered, otherwise the reason to put in front of the operator. */
        Optional<String> failure(String jdbcUrl, String user, String password);
    }

    /**
     * What the check found: the one line that names the backend and its address, whatever the
     * configuration deserves a warning about, and the refusal that stops the start.
     *
     * @param line    the startup line, or null when the start is refused
     * @param warnings zero or more warnings, all of them survivable
     * @param refusal the block to print before stopping, or null when the start may go on
     */
    record StartupReport(String line, List<String> warnings, String refusal) {
    }

    /**
     * Last of the listeners for this event, which is what makes this the first point where the
     * configuration is complete: the one that loads application.properties runs at the other end
     * of the order, and everything this class reads, the log destination included, would otherwise
     * be resolved from the command line alone. Stated rather than inherited, because an unordered
     * listener has no promise about where it lands.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();

        // The log destination is a plain system property everywhere else, because the console
        // entry points have no Spring environment to read. Handing the resolved value over here
        // is what makes the key behave on the API path the way the documents describe it: from
        // application.properties, from the command line or from a variable.
        AppLogger.useLogFile(environment.getProperty(MinibankProperties.LOG_FILE));

        StartupReport report = inspect(environment, ApiStartupCheck::probe);
        for (String warning : report.warnings()) {
            AppLogger.warn("api", warning);
        }

        if (report.refusal() != null) {
            AppLogger.error("api", firstLine(report.refusal()), null);
            System.out.println();
            System.out.println(report.refusal());
            System.out.flush();
            // Nothing has been built yet, so there is nothing to close and nothing to unwind.
            // Throwing instead would put a stack trace in front of an operator whose only
            // problem is that a server is not running.
            System.exit(1);
        }

        AppLogger.info("api", report.line());
    }

    /**
     * The whole decision, from configuration to text.
     *
     * @param environment where the keys are resolved from
     * @param probe       what to ask whether the database answers
     */
    static StartupReport inspect(Environment environment, DatabaseProbe probe) {
        List<String> warnings = renamedKeyWarnings(environment);

        String storage = environment.getProperty(
                MinibankProperties.STORAGE, MinibankProperties.STORAGE_DEFAULT);

        if (namesJson(storage)) {
            String path = environment.getProperty(
                    MinibankProperties.JSON_PATH, MinibankProperties.JSON_PATH_DEFAULT);
            return new StartupReport(jsonStartupLine(path), warnings, null);
        }

        if (!namesSql(storage)) {
            return new StartupReport(null, warnings, unknownStorageReport(storage));
        }

        String url = environment.getProperty(
                MinibankProperties.SQL_URL, MinibankProperties.SQL_URL_DEFAULT);
        String user = environment.getProperty(
                MinibankProperties.SQL_USER, MinibankProperties.SQL_USER_DEFAULT);
        String password = environment.getProperty(
                MinibankProperties.SQL_PASSWORD, MinibankProperties.SQL_PASSWORD_DEFAULT);

        Optional<String> failure = probe.failure(url, user, password);
        return failure
                .map(reason -> new StartupReport(
                        null, warnings, unreachableReport(storage, url, user, reason)))
                .orElseGet(() -> new StartupReport(sqlStartupLine(url, user), warnings, null));
    }

    // -------------------------------------------------------------------------
    // The vocabulary of minibank.storage, in one place
    // -------------------------------------------------------------------------

    /**
     * Three spellings because all three are things an operator types, and because
     * {@link MinibankApiConfig#bootstrap} has always accepted them. Both read this rather than
     * carry a list each: a value this class calls unknown and that one calls SQL would refuse a
     * start that would have worked.
     */
    static boolean namesSql(String storage) {
        return "sql".equalsIgnoreCase(storage)
                || "postgres".equalsIgnoreCase(storage)
                || "postgresql".equalsIgnoreCase(storage);
    }

    static boolean namesJson(String storage) {
        return "json".equalsIgnoreCase(storage);
    }

    // -------------------------------------------------------------------------
    // The text
    // -------------------------------------------------------------------------

    static String sqlStartupLine(String jdbcUrl, String user) {
        return "Storage is PostgreSQL at " + address(jdbcUrl) + " as user " + user
                + ", and it answered";
    }

    /**
     * The absolute path, because the configured one is relative and what it resolves to depends
     * on the directory the API was started from, which is the part an operator cannot see.
     */
    static String jsonStartupLine(String path) {
        return "Storage is the JSON store at " + absolute(path);
    }

    /**
     * The configured value of {@code minibank.storage} is quoted back rather than the word sql,
     * because three spellings reach here and being told the key says something it does not say is
     * how an operator starts looking for a second configuration file.
     */
    static String unreachableReport(String storage, String jdbcUrl, String user, String reason) {
        return """
                === MiniBank cannot start ===
                %s is '%s', so the API needs PostgreSQL, and nothing answered at
                %s as user %s.
                Reason: %s

                Start the database this project ships with. It answers at the address
                %s already defaults to, so the command is all of it:
                    docker compose up -d
                Point the API at another server instead:
                    -D%s=jdbc:postgresql://host:port/database -D%s=... -D%s=...
                Or start without a database at all, on the JSON store:
                    -D%s=json"""
                .formatted(
                        MinibankProperties.STORAGE, storage,
                        address(jdbcUrl), user, reason,
                        MinibankProperties.SQL_URL,
                        MinibankProperties.SQL_URL,
                        MinibankProperties.SQL_USER,
                        MinibankProperties.SQL_PASSWORD,
                        MinibankProperties.STORAGE);
    }

    static String unknownStorageReport(String storage) {
        return """
                === MiniBank cannot start ===
                %s is '%s', which is neither json nor sql, so there is no store to open.
                Choose one:
                    -D%s=json
                    -D%s=sql"""
                .formatted(
                        MinibankProperties.STORAGE, storage,
                        MinibankProperties.STORAGE,
                        MinibankProperties.STORAGE);
    }

    /**
     * The console entry points refuse to start on an old key name; the API cannot, because it
     * resolves the same keys through placeholders that never see one. This is where it notices.
     *
     * A warning and not a refusal: by the time this runs the value in use is already decided, and
     * saying which value that is answers the question the old name raises. The value is quoted
     * for the two keys where it helps and withheld for the password.
     */
    static List<String> renamedKeyWarnings(Environment environment) {
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, String> renamed : MinibankProperties.RENAMED_KEYS.entrySet()) {
            String legacyKey = renamed.getKey();
            String currentKey = renamed.getValue();
            if (!environment.containsProperty(legacyKey)) {
                continue;
            }
            warnings.add(renamedKeyWarning(legacyKey, currentKey, valueToQuote(environment, currentKey)));
        }
        return warnings;
    }

    static String renamedKeyWarning(String legacyKey, String currentKey, String valueInUse) {
        String warning = legacyKey + " was renamed to " + currentKey + " and is not read any more.";
        if (valueInUse != null) {
            warning += " The value in use is " + currentKey + " = " + valueInUse;
        }
        return warning;
    }

    /** Null for the password, and the resolved value, defaults included, for the rest. */
    private static String valueToQuote(Environment environment, String currentKey) {
        if (MinibankProperties.SQL_PASSWORD.equals(currentKey)) {
            return null;
        }
        if (MinibankProperties.SQL_URL.equals(currentKey)) {
            return address(environment.getProperty(currentKey, MinibankProperties.SQL_URL_DEFAULT));
        }
        return environment.getProperty(currentKey, MinibankProperties.SQL_USER_DEFAULT);
    }

    /**
     * A JDBC url with its parameters removed, which is what may be printed.
     *
     * The parameters can carry the password, and everything printed here goes to a console and to
     * a log file that outlive the process.
     */
    static String address(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "";
        }
        int parameters = jdbcUrl.indexOf('?');
        return parameters < 0 ? jdbcUrl : jdbcUrl.substring(0, parameters) + " (parameters omitted)";
    }

    /**
     * The reason to show for a failure, which is its first line: the driver's message is a
     * sentence worth repeating, and anything after the first line is a second failure wrapped in
     * the first.
     */
    static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "no reason given";
        }
        int end = text.indexOf('\n');
        return (end < 0 ? text : text.substring(0, end)).trim();
    }

    private static String absolute(String path) {
        try {
            return Paths.get(path).toAbsolutePath().toString();
        } catch (InvalidPathException e) {
            // The key can hold anything; a value that is not a path at all is still worth
            // printing back, since it is what the operator has to correct.
            return path;
        }
    }

    /**
     * The probe itself. The timeout is put back afterwards because it is a property of the whole
     * driver manager and the application opens its own connections through it.
     */
    private static Optional<String> probe(String jdbcUrl, String user, String password) {
        int previousTimeout = DriverManager.getLoginTimeout();
        DriverManager.setLoginTimeout(PROBE_TIMEOUT_SECONDS);
        try (Connection ignored = DriverManager.getConnection(jdbcUrl, user, password)) {
            return Optional.empty();
        } catch (Exception e) {
            return Optional.of(firstLine(e.getMessage()));
        } finally {
            DriverManager.setLoginTimeout(previousTimeout);
        }
    }
}
