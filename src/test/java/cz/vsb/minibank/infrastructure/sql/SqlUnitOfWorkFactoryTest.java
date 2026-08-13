package cz.vsb.minibank.infrastructure.sql;

import cz.vsb.minibank.domain.DomainEventBus;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What happens to a connection the factory opens but cannot use.
 *
 * There is no pool in this project, so a connection that is opened and then dropped on the floor
 * is a PostgreSQL backend slot held until the process exits. The factory used to open one and
 * call setAutoCommit on it with nothing in between, so a server that terminated the session in
 * that gap left the connection open and only the wrapped SQLException came back out. A caller
 * that retries against a flapping database walks the server toward max_connections that way.
 *
 * No database is involved here. A stub {@link Driver} is registered under a URL prefix of this
 * test's own and hands back a dynamic proxy that refuses setAutoCommit and records whether it
 * was closed, which is the whole of what needs asserting. The driver is deregistered afterwards
 * because DriverManager is process wide and the suite shares one JVM with the SQL tests.
 */
class SqlUnitOfWorkFactoryTest {

    private static final String URL_PREFIX = "jdbc:minibank-test-stub:";
    private static final String URL = URL_PREFIX + "unit-of-work";

    private StubDriver driver;

    @BeforeEach
    void registerStubDriver() throws SQLException {
        driver = new StubDriver();
        DriverManager.registerDriver(driver);
    }

    @AfterEach
    void deregisterStubDriver() throws SQLException {
        DriverManager.deregisterDriver(driver);
    }

    @Test
    void aConnectionThatRefusesToLeaveAutoCommitIsClosedRatherThanStranded() {
        driver.refuseSetAutoCommitWith(new SQLException("terminating connection due to administrator command"));

        RuntimeException failure = assertThrows(RuntimeException.class, factory()::begin);

        assertEquals("Failed to open SQL UnitOfWork", failure.getMessage());
        assertTrue(driver.handedOut().wasClosed(),
                "a connection that never reaches a SqlUnitOfWork has nobody else to close it");
    }

    /**
     * The close is a cleanup, not a second chance to fail. A server that has already gone away
     * can refuse it, and if that took over the stack trace the caller would be told about the
     * close instead of about the reason there was something to close.
     */
    @Test
    void aCloseThatAlsoFailsDoesNotHideWhyTheConnectionWasAbandoned() {
        SQLException configureFailure = new SQLException("terminating connection due to administrator command");
        driver.refuseSetAutoCommitWith(configureFailure);
        driver.refuseCloseWith(new SQLException("socket already closed"));

        RuntimeException failure = assertThrows(RuntimeException.class, factory()::begin);

        assertEquals("Failed to open SQL UnitOfWork", failure.getMessage());
        assertSame(configureFailure, failure.getCause(), "the original failure is what the caller sees");
        assertEquals(1, configureFailure.getSuppressed().length,
                "the failed close is attached to it rather than thrown over it");
        assertEquals("socket already closed", configureFailure.getSuppressed()[0].getMessage());
    }

    /**
     * The other half of the fix: a connection that configures cleanly must still be handed over
     * open, since the unit of work is about to run a transaction on it.
     */
    @Test
    void aConnectionThatConfiguresIsHandedToTheUnitOfWorkStillOpen() {
        UnitOfWork uow = factory().begin();

        assertInstanceOf(SqlUnitOfWork.class, uow);
        assertFalse(driver.handedOut().wasClosed(), "the unit of work closes it, not the factory");
        assertEquals(Boolean.FALSE, driver.handedOut().autoCommit(),
                "the unit of work owns the transaction boundary, so auto-commit must be off");
        assertSame(driver.handedOut().connection(), ((SqlUnitOfWork) uow).connection());
    }

    private SqlUnitOfWorkFactory factory() {
        return new SqlUnitOfWorkFactory(URL, "stub-user", "stub-password", new DomainEventBus());
    }

    /**
     * A JDBC driver that answers only this test's URL prefix and hands out one scripted
     * connection. DriverManager asks every registered driver in turn and the PostgreSQL driver
     * declines anything that is not its own prefix, so registering this one disturbs nothing.
     */
    private static final class StubDriver implements Driver {

        private SQLException setAutoCommitFailure;
        private SQLException closeFailure;
        private StubConnection handedOut;

        void refuseSetAutoCommitWith(SQLException failure) {
            this.setAutoCommitFailure = failure;
        }

        void refuseCloseWith(SQLException failure) {
            this.closeFailure = failure;
        }

        StubConnection handedOut() {
            assertNotNull(handedOut, "the factory never asked for a connection");
            return handedOut;
        }

        @Override
        public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) {
                return null;
            }
            handedOut = new StubConnection(setAutoCommitFailure, closeFailure);
            return handedOut.connection();
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith(URL_PREFIX);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    /**
     * A java.sql.Connection built as a dynamic proxy, because implementing that interface by hand
     * would be several hundred lines of methods nothing here calls. Anything the factory is not
     * expected to touch fails loudly instead of quietly returning a default.
     */
    private static final class StubConnection implements InvocationHandler {

        private final SQLException setAutoCommitFailure;
        private final SQLException closeFailure;
        private final Connection connection;
        private Boolean autoCommit;
        private boolean closed;

        StubConnection(SQLException setAutoCommitFailure, SQLException closeFailure) {
            this.setAutoCommitFailure = setAutoCommitFailure;
            this.closeFailure = closeFailure;
            this.connection = (Connection) Proxy.newProxyInstance(
                    SqlUnitOfWorkFactoryTest.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    this);
        }

        Connection connection() {
            return connection;
        }

        boolean wasClosed() {
            return closed;
        }

        Boolean autoCommit() {
            return autoCommit;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
            switch (method.getName()) {
                case "setAutoCommit":
                    if (setAutoCommitFailure != null) {
                        throw setAutoCommitFailure;
                    }
                    autoCommit = (Boolean) args[0];
                    return null;
                case "close":
                    closed = true;
                    if (closeFailure != null) {
                        throw closeFailure;
                    }
                    return null;
                case "isClosed":
                    return closed;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "stub connection";
                default:
                    throw new UnsupportedOperationException(
                            "the factory is not expected to call " + method.getName());
            }
        }
    }
}
