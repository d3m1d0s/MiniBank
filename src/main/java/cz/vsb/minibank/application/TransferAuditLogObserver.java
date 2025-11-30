package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferObserver;
import cz.vsb.minibank.domain.TransferStatus;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Simple observer that prints an audit log line
 * whenever a transfer status changes.
 */
public class TransferAuditLogObserver implements TransferObserver {

    private static final Path LOG_FILE = Paths.get("transfer_audit.log");

    @Override
    public void onStatusChanged(Transfer transfer,
                                TransferStatus oldStatus,
                                TransferStatus newStatus) {
        String line = String.format(
                "%s Transfer %d: %s -> %s%n",
                Instant.now(),
                transfer.id(),
                oldStatus,
                newStatus
        );

        // 1) to console
        System.out.print("[AUDIT] " + line);

        // 2) to file
        try (BufferedWriter out = Files.newBufferedWriter(
                LOG_FILE,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        )) {
            out.write(line);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
