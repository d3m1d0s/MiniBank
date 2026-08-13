package cz.vsb.minibank.infrastructure.json;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * One process's exclusive claim on one JSON store file.
 *
 * {@link JsonDataStore} coordinates threads and nothing else. It reads the whole document once, at
 * startup, keeps it in a Bundle behind a ReentrantLock, and rewrites all of it from that cache on
 * every commit; nothing reloads before a write. Two processes opened on one file therefore hold
 * two independent snapshots, and the second one to save publishes its own over the first: settled
 * transfers, debited balances, everything the other one committed in between. Each also bumps its
 * own copy of the id sequences, so both can mint the same id, and nothing in the file format
 * notices afterwards that any of it happened.
 *
 * The answer is to refuse rather than to coordinate. Sharing this file between processes would
 * mean reloading and merging inside every commit, several hundred lines of storage engine written
 * to deliver what the SQL backend already delivers properly. So the second process is turned away
 * at startup with a message naming the file, and anyone who really wants two writers points both
 * at PostgreSQL.
 *
 * What is locked is a sidecar, {@code <store>.lock}, and never the store itself. An exclusive lock
 * on data.json defeats the publish it would be protecting: {@link JsonDataStore#save()} replaces
 * the store by moving a temp file over it, and on Windows a lock held on the target stops that
 * move. Locks being mandatory there rather than advisory, it would also shut the holder out of its
 * own reads. The sidecar is never read and never written, and its emptiness is deliberate: a
 * Windows lock keeps every other process from reading whatever was put in it, so there is nothing
 * useful to write there.
 *
 * Claims are reference counted per canonical path, because a file lock belongs to the JVM rather
 * than to the channel that took it: a second overlapping lock on one file from one JVM throws
 * {@link OverlappingFileLockException} instead of waiting. Opening a second Bootstrap over one
 * store in one process is ordinary - it is how a caller reads back what it has just published - so
 * the second one joins the claim the first took, and the file is unlocked when the last holder
 * gives it back.
 *
 * A claim is held until {@link #close()}, and nothing in the application closes one: the entry
 * points use their store until the process exits, and the operating system drops the lock then,
 * however the process ended. That is also why a lock file left behind by a killed run is not stale
 * and must not be deleted by hand to "unstick" anything: the claim lives with the process, not with
 * the file, and the file is only ever an anchor for it.
 */
public final class JsonStoreGuard implements AutoCloseable {

    /**
     * What a store's lock file is called: the store's own name plus this. A constant because the
     * store path is the caller's and this is the only thing added to it, so a change here changes
     * which file every running process is holding.
     */
    static final String LOCK_SUFFIX = ".lock";

    /**
     * The claims this JVM holds, keyed by the canonical path of the lock file. Its own monitor
     * guards the map and makes taking and giving back a claim atomic against each other, which is
     * what keeps the count and the lock from disagreeing.
     */
    private static final Map<Path, Claim> CLAIMS = new HashMap<>();

    /**
     * One held lock and the number of guards standing on it. Only the channel is kept: closing a
     * channel releases the locks it holds, so there is nothing else to do with the FileLock.
     */
    private static final class Claim {
        private final FileChannel channel;
        private int holders = 1;

        private Claim(FileChannel channel) {
            this.channel = channel;
        }
    }

    private final Path lockFile;

    /** Makes close() idempotent, so closing one holder twice cannot free another holder's claim. */
    private boolean closed;

    private JsonStoreGuard(Path lockFile) {
        this.lockFile = lockFile;
    }

    /**
     * Claims the store at the given path for this process, or refuses when another one holds it.
     *
     * Call this before the file is read. Opening a store sweeps its directory and every commit
     * afterwards rewrites the whole document, so a process that has no business here has to be told
     * before it touches any of that.
     *
     * @param storePath path of the JSON store file, spelled as the caller spells it
     * @return the claim, which the caller keeps for as long as it uses the store
     * @throws IllegalStateException if another process holds the store, or the lock file cannot be
     *         created next to it
     */
    public static JsonStoreGuard acquire(String storePath) {
        Path lockFile = lockFileFor(storePath);
        synchronized (CLAIMS) {
            Claim claim = CLAIMS.get(lockFile);
            if (claim != null) {
                claim.holders++;
                return new JsonStoreGuard(lockFile);
            }

            FileChannel channel = null;
            boolean kept = false;
            try {
                channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                if (channel.tryLock() == null) {
                    // What a second process gets: the lock is held elsewhere on this machine.
                    throw storeIsInUse(storePath, lockFile);
                }
                CLAIMS.put(lockFile, new Claim(channel));
                kept = true;
                return new JsonStoreGuard(lockFile);
            } catch (OverlappingFileLockException heldInThisJvm) {
                // The same file locked twice in one JVM, which the map above is meant to prevent.
                // Reaching this means two spellings of one path got past the canonical form, and
                // treating that as anything other than "the store is taken" would hand the caller
                // a runtime exception nobody documents instead of the one sentence they can act on.
                throw storeIsInUse(storePath, lockFile);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot claim the JSON data store at '" + storePath + "': its lock file '"
                                + lockFile + "' could not be opened.", e);
            } finally {
                // Every path out of here except the successful one leaves a channel nobody owns.
                if (!kept) {
                    closeQuietly(channel);
                }
            }
        }
    }

    /**
     * Gives this holder's claim back, and unlocks the file once the last holder has.
     *
     * Nothing in the application calls this. It is here for callers that own a lifecycle of their
     * own and open many stores in one JVM, and for the symmetry that makes the count something
     * other than a one-way counter.
     */
    @Override
    public void close() {
        synchronized (CLAIMS) {
            if (closed) {
                return;
            }
            closed = true;

            Claim claim = CLAIMS.get(lockFile);
            if (claim == null || --claim.holders > 0) {
                // Either somebody else is still using the store, or there is no claim under this
                // key at all, which every guard being handed out by acquire makes unreachable.
                return;
            }
            CLAIMS.remove(lockFile);
            closeQuietly(claim.channel);
        }
    }

    /**
     * Works out the lock file for a store, makes sure it exists, and returns it in the one spelling
     * this JVM keys its claims by.
     *
     * The real path is what matters on Windows, where one file answers to more than one name: a
     * store opened as data.json and again as DATA.json is the same file, and the second lock on one
     * file in one JVM throws rather than joining the first. Both spellings therefore have to land
     * on one map entry. Resolving a path to its real one requires the file to exist, which is why
     * it is created first.
     */
    private static Path lockFileFor(String storePath) {
        Path store = Paths.get(storePath).toAbsolutePath().normalize();
        Path lockFile = store.resolveSibling(store.getFileName() + LOCK_SUFFIX);
        try {
            Path directory = lockFile.getParent();
            if (directory != null) {
                // The store's own directory is created by JsonDataStore, but the claim is taken
                // before there is a store, so this cannot wait for it.
                Files.createDirectories(directory);
            }
            try {
                Files.createFile(lockFile);
            } catch (FileAlreadyExistsException leftByAnEarlierRun) {
                // Expected: the file outlives every run that locks it, and nothing reads its
                // content, so there is nothing to reconcile with what an earlier run left.
            }
            return lockFile.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot claim the JSON data store at '" + storePath + "': its lock file '"
                            + lockFile + "' could not be created. The store's directory has to be"
                            + " writable, or the store cannot be used at all.", e);
        }
    }

    /**
     * The refusal a second process meets.
     *
     * It names both files and both ways out, because whoever runs into this is looking at a console
     * and not at this class. The two property names are spelled out rather than read from
     * MinibankProperties, which lives in the layer above this one.
     */
    private static IllegalStateException storeIsInUse(String storePath, Path lockFile) {
        return new IllegalStateException(
                "The JSON data store at '" + storePath + "' is already open in another process,"
                        + " which holds '" + lockFile + "'. Two processes on one JSON store each"
                        + " keep their own copy of the whole document, and the second one to save"
                        + " overwrites everything the first wrote, so this process stops instead of"
                        + " starting. Close the other one, or give this one a store of its own with"
                        + " -Dminibank.json.path=<file>, or point both at PostgreSQL with"
                        + " -Dminibank.storage=sql, which is built for more than one writer.");
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException nothingToDoAboutIt) {
            // Giving a store back is nowhere to fail the caller: the claim is gone from the map
            // either way, and the operating system drops the lock when the process ends.
        }
    }
}
