package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertState;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.value.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the two audit writers actually put in the log.
 *
 * Neither had a test. Both used to run hundreds of times per suite because every
 * BootstrapServices attached another copy, but nothing ever asserted anything about the result,
 * which is how the fraud writer kept a second timestamp of its own for as long as it did.
 */
class AuditLogObserverTest {

    @TempDir
    Path tempDir;

    private Path logPath;
    private String previousLogFile;

    @BeforeEach
    void redirectTheLog() throws IOException {
        logPath = tempDir.resolve("minibank.log");
        // Surefire points the whole suite at target/; restore that afterwards
        previousLogFile = System.getProperty(MinibankProperties.LOG_FILE);
        System.setProperty(MinibankProperties.LOG_FILE, logPath.toString());
        Files.deleteIfExists(logPath);
        SecurityContext.clear();
    }

    @AfterEach
    void restoreTheLog() {
        if (previousLogFile == null) {
            System.clearProperty(MinibankProperties.LOG_FILE);
        } else {
            System.setProperty(MinibankProperties.LOG_FILE, previousLogFile);
        }
        SecurityContext.clear();
    }

    @Test
    void theTransferWriterNamesTheTransferItsMoveAndItsAccount() throws IOException {
        Transfer transfer = new Transfer(42, 7, null,
                "CZ2001000000000012345678", Money.czk(1500), "CZK");

        new TransferAuditLogObserver().onStatusChanged(
                transfer, TransferStatus.CREATED, TransferStatus.WAITING_AUTH);

        assertEquals(
                "Transfer 42: CREATED -> WAITING_AUTH, amount=1500.00 CZK, sourceAccountId=7",
                singleAuditMessage("audit.transfer"));
    }

    @Test
    void theFraudWriterNamesTheAlertItsTransferAndItsReason() throws IOException {
        FraudAlert alert = new FraudAlert(15, 42, "Amount above the alert threshold");

        new FraudAlertAuditLogObserver().onStateChanged(
                alert, FraudAlertState.NEW, FraudAlertState.SUSPICIOUS);

        String message = singleAuditMessage("audit.fraud");

        assertEquals(
                "FraudAlert 15 for transfer 42: NEW -> SUSPICIOUS, "
                        + "reason=Amount above the alert threshold",
                message);

        // The line AppLogger writes already opens with an instant. A writer that adds its own
        // puts two on every line, which is what this one used to do.
        assertFalse(message.startsWith("20"),
                "The message must not begin with a timestamp of its own");
    }

    /**
     * The part of the single logged line that the observer supplied, with everything
     * {@link AppLogger} adds stripped off.
     */
    private String singleAuditMessage(String expectedCategory) throws IOException {
        List<String> lines = Files.readAllLines(logPath);
        assertEquals(1, lines.size(), "Expected exactly one logged line");

        String line = lines.get(0);
        String prefix = " AUDIT [" + expectedCategory + "] user=<none> | ";
        int at = line.indexOf(prefix);
        assertTrue(at > 0,
                "Expected an AUDIT line in category " + expectedCategory + ", got: " + line);

        return line.substring(at + prefix.length());
    }
}
