package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim a process takes on a JSON store, and what it does to everyone else who wants one.
 *
 * The defect behind it is a lost store rather than a lost update. The JSON adapter reads the whole
 * document once and rewrites all of it from that cache on every commit, so a second process opened
 * on the same file overwrites everything the first one committed, and mints the same ids while it
 * is at it. Both entry points default to the same file, so two of them in one directory is not an
 * exotic setup: it is what happens when someone leaves the console app running and starts the API.
 *
 * What this test does not cover, and it is half of it. No second JVM is started - nothing in this
 * suite ever has, and a spawned process is a large cost for one case - so the genuine cross-process
 * path is unproven here: a real second process finds the lock held by someone outside its own JVM,
 * where {@code tryLock} returns null instead of throwing. The case below that meets a locked store
 * takes the lock from a foreign channel inside this JVM instead, which reaches the same refusal
 * down the other branch. Unproven with it: that the claim survives for as long as the process does,
 * and that the operating system gives it back when the process ends. What is covered is everything
 * one JVM can answer for - that opening the same store twice in one process still works, that the
 * count gives the file back exactly once, that a neighbouring store is unaffected, that SQL mode
 * claims nothing, and that a refusal names the file and offers a way out.
 *
 * One thing is covered by being ordinary rather than by an assertion of its own: the first case
 * publishes a document while the claim is held. Locking the store file itself instead of a sidecar
 * would break exactly that, because publishing means moving a temp file over the store.
 */
class JsonStoreGuardTest {

    @TempDir
    Path tempDir;

    private static final int ACCOUNT_ID = 100;
    private static final String ACCOUNT_IBAN = "CZ6508000000192000145399";

    /**
     * Two Bootstraps over one file in one process, which is ordinary: it is how a caller reads back
     * what it has just published. The claim has to be shared for that, because one JVM cannot hold
     * two overlapping locks on one file - the second attempt throws rather than waiting.
     */
    @Test
    void twoBootstrapsOverOneStoreInOneProcessBothWork() throws Exception {
        Path store = tempDir.resolve("data.json");

        Bootstrap writer = new Bootstrap(store.toString());
        writer.accounts.save(new Account(ACCOUNT_ID, new IBAN(ACCOUNT_IBAN), Money.czk(5_000)));

        Bootstrap reader = new Bootstrap(store.toString());

        assertEquals(ACCOUNT_ID, reader.accounts.byId(ACCOUNT_ID).orElseThrow().id(),
                "the second Bootstrap must read the document the first one published while the"
                        + " claim was held");
        assertTrue(isClaimedByThisJvm(lockFileOf(store)),
                "and the store must be claimed the whole time either of them is open");

        reader.storeGuard.close();
        writer.storeGuard.close();
    }

    /**
     * The count, from both ends. Giving one holder's claim back while another is still using the
     * store would unlock a file this process is about to rewrite, which is the very thing the
     * refusal exists to prevent.
     */
    @Test
    void theClaimIsGivenBackOnlyWhenTheLastHolderHasClosed() throws Exception {
        Path store = tempDir.resolve("data.json");
        Path lockFile = lockFileOf(store);

        JsonStoreGuard first = JsonStoreGuard.acquire(store.toString());
        JsonStoreGuard second = JsonStoreGuard.acquire(store.toString());

        assertTrue(isClaimedByThisJvm(lockFile), "a claimed store is locked");

        first.close();
        assertTrue(isClaimedByThisJvm(lockFile),
                "one holder leaving must not unlock a store the other one is still using");

        first.close();
        assertTrue(isClaimedByThisJvm(lockFile),
                "and closing the same holder twice must not hand back the other one's claim");

        second.close();
        assertFalse(isClaimedByThisJvm(lockFile),
                "the last holder out unlocks the file, so the next process can have it");
    }

    /**
     * The refusal itself. The lock is taken here from a channel of this test's own, so the guard
     * meets it as a lock it cannot have; a second process meets the same lock down the other branch
     * of the same decision. The assertions are about what the person who ran that second process
     * reads on their console, because that is all they get.
     */
    @Test
    void aStoreThatIsAlreadyLockedIsRefusedByNameAndWithAWayOut() throws Exception {
        Path store = tempDir.resolve("data.json");

        try (FileChannel elsewhere = FileChannel.open(lockFileOf(store),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock held = elsewhere.tryLock()) {

            assertNotNull(held, "the fixture has to hold the lock for this case to mean anything");

            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> new Bootstrap(store.toString()));

            assertFalse(refused instanceof OverlappingFileLockException,
                    "the raw NIO failure says nothing anyone can act on and must not get out: "
                            + refused);
            assertTrue(refused.getMessage().contains(store.toString()),
                    "the refusal must name the store: " + refused.getMessage());
            assertTrue(refused.getMessage().contains("minibank.json.path"),
                    "and must say what to do about it: " + refused.getMessage());
            assertFalse(Files.exists(store),
                    "a process that was refused must not have touched the store it was refused");
        }

        // And the refused attempt must leave nothing half taken behind it: the store is free again
        // as soon as the holder is, not for the rest of this JVM's life.
        Bootstrap infra = new Bootstrap(store.toString());
        assertNotNull(infra.store);
        infra.storeGuard.close();
    }

    /**
     * A Bootstrap that threw on the way up must not keep the store.
     *
     * The claim is taken before the document is read, so an unreadable file fails after it has
     * been taken. Leaving it standing would hold the path for the rest of the process, and the
     * retry after the file has been fixed would then be refused by a message naming another
     * process when the process it means is this one.
     */
    @Test
    void aBootstrapThatFailedToOpenGivesTheStoreBack() throws Exception {
        Path store = tempDir.resolve("data.json");
        Files.writeString(store, "{ this is not json");

        assertThrows(IllegalStateException.class, () -> new Bootstrap(store.toString()));

        assertFalse(isClaimedByThisJvm(lockFileOf(store)),
                "a construction that threw must not leave the store claimed behind it");

        Files.writeString(store, "");
        Bootstrap retried = new Bootstrap(store.toString());
        assertNotNull(retried.store, "and the retry, once the file is readable, must open normally");
        retried.storeGuard.close();
    }

    /**
     * One claim per store, not one per process. The demo runner keeps its own file beside the
     * console app's on purpose, and it has to keep working while that one is open.
     */
    @Test
    void aClaimOnOneStoreLeavesTheOneBesideItFree() throws Exception {
        Path claimed = tempDir.resolve("data.json");
        Path beside = tempDir.resolve("demo.json");

        JsonStoreGuard guard = JsonStoreGuard.acquire(claimed.toString());
        try {
            Bootstrap neighbour = new Bootstrap(beside.toString());

            assertTrue(isClaimedByThisJvm(lockFileOf(beside)),
                    "a store in the same directory takes a claim of its own");

            neighbour.storeGuard.close();
            assertFalse(isClaimedByThisJvm(lockFileOf(beside)),
                    "which it gives back on its own");
            assertTrue(isClaimedByThisJvm(lockFileOf(claimed)),
                    "and none of that reaches the store this case is holding");
        } finally {
            guard.close();
        }
    }

    /**
     * SQL mode is where a second writer is sent, so it cannot be the mode that refuses one. Nothing
     * here needs a database: the SQL constructors only record the connection details.
     */
    @Test
    void sqlModeTakesNoClaimAtAll() {
        Bootstrap infra = new Bootstrap("jdbc:postgresql://localhost:1/nothing", "unused", "unused");

        assertNull(infra.store, "SQL mode has no JSON store");
        assertNull(infra.storeGuard,
                "and nothing to claim: PostgreSQL is what arbitrates between processes there");
    }

    private static Path lockFileOf(Path store) {
        // Through the constant the guard itself uses, so the two cannot drift onto different files
        // and leave this asserting against something nobody locks.
        return store.resolveSibling(store.getFileName() + JsonStoreGuard.LOCK_SUFFIX);
    }

    /**
     * Whether the lock file is claimed, asked the way another holder in this JVM finds out: a
     * second overlapping lock on one file from one JVM is refused by the JVM itself, so the throw
     * is the answer. A null from tryLock would mean a process outside this one holds the file,
     * which nothing here starts, so it is reported rather than folded into "not claimed".
     */
    private static boolean isClaimedByThisJvm(Path lockFile) throws Exception {
        try (FileChannel probe = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
            FileLock taken = probe.tryLock();
            assertNotNull(taken, "nothing outside this JVM should be holding " + lockFile);
            taken.release();
            return false;
        } catch (OverlappingFileLockException claimedHere) {
            return true;
        }
    }
}
