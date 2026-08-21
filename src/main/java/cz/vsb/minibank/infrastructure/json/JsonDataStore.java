package cz.vsb.minibank.infrastructure.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import cz.vsb.minibank.infrastructure.json.dto.JsonAccount;
import cz.vsb.minibank.infrastructure.json.dto.JsonCustomer;
import cz.vsb.minibank.infrastructure.json.dto.JsonFraudAlert;
import cz.vsb.minibank.infrastructure.json.dto.JsonFraudAlertNote;
import cz.vsb.minibank.infrastructure.json.dto.JsonTransfer;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * JSON-based persistence store for customers, accounts, transfers and fraud alerts,
 * including centralized ID sequences.
 */
public class JsonDataStore {
    private final ObjectMapper om;
    private final File file;

    /**
     * Centralized ID sequences that are serialized into JSON.
     */
    public static class Sequences {
        public int customer = 1;
        public int account = 100;
        public int beneficiary = 10;
        public int transfer = 5001;
        public int fraudAlert = 9001;
    }

    /**
     * Container for all persisted entities and their ID sequences.
     */
    public static class Bundle {
        public List<JsonCustomer> customers = new ArrayList<>();
        public List<JsonAccount> accounts = new ArrayList<>();
        public List<JsonTransfer> transfers = new ArrayList<>();
        public List<JsonFraudAlert> fraudAlerts = new ArrayList<>();

        /**
         * The analysts' notes, one record per note, appended and never rewritten.
         *
         * A list of its own rather than a field on each alert, which mirrors the fraud_alert_notes
         * table on the SQL backend and keeps the journal out of reach of the one operation that
         * could lose it: saving an alert replaces its record with a fresh one built from the
         * aggregate, and the aggregate does not carry its journal.
         */
        public List<JsonFraudAlertNote> fraudAlertNotes = new ArrayList<>();

        // Sequence section
        public Sequences sequences = new Sequences();
    }

    private Bundle cache = new Bundle();

    /**
     * The one lock for the whole store. It guards the cache field, the five lists inside
     * the Bundle, every DTO reachable from them (including the nested accountIds,
     * beneficiaries and tags lists), the sequences, and the backing file.
     *
     * One store-wide lock is deliberate. This is a single JSON file and every commit
     * rewrites all of it, so there is nothing finer-grained worth locking, and a coarse
     * lock that is actually correct beats a fine one that is not. The cost is that JSON
     * transactions run one at a time.
     *
     * It is a ReentrantLock rather than a synchronized block because a JsonUnitOfWork
     * holds it from begin() until commit() or rollback(), which crosses method
     * boundaries, and because calls made inside that window re-acquire it reentrantly.
     *
     * Because the lock spans a whole transaction, anything a transaction does runs
     * inside it - including AppLogger's audit writes to stderr and minibank.log.
     * Nothing invoked between begin() and commit() may block indefinitely, or it
     * blocks every other thread that touches the store; that rule is why
     * PaymentDispatcher calls the payment gateway between units of work rather than
     * from inside one.
     *
     * Threads are all this lock coordinates. A second process on the same file is
     * refused outright at startup - JsonStoreGuard holds that contract, and says
     * why the answer is refusal rather than coordination.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Acquires the store lock. Must be paired with unlock() in a finally block.
     */
    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }

    /**
     * Runs a read against the Bundle under the store lock. Whatever the body returns
     * must not alias anything inside the Bundle - map or copy before returning.
     */
    public <T> T read(Function<Bundle, T> body) {
        lock.lock();
        try {
            return body.apply(cache);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies a mutation to the Bundle and persists it as a single lock hold. This is the
     * path for callers with no unit of work bound, where the mutation and the save would
     * otherwise be two independently locked steps that another thread can write between.
     *
     * The mutation's own exceptions propagate unchanged; only the checked exception from
     * save() is wrapped. Callers rely on that: a DomainException raised inside a mutation
     * has to reach RestExceptionHandler as itself, not as a generic RuntimeException.
     *
     * A save that fails has already left the mutation in the shared Bundle, so the failure is
     * reported only once the store has been put back to the last state that reached disk, exactly
     * as a failed commit does. Otherwise the next write publishes the change that was just
     * refused, and that write need not be a deliberate one: every nextXxxId() saves the whole
     * cache in order to bump one sequence, so allocating an id is enough to persist it. The
     * revert re-enters the lock this hold already owns and releases only its own acquisition, so
     * the mutation and its undo remain a single hold.
     */
    public void mutateAndSave(Runnable mutation) {
        lock.lock();
        try {
            mutation.run();
            try {
                save();
            } catch (Exception e) {
                discardChanges(e);
                throw new RuntimeException(e);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Throws away in-memory changes by re-reading the file.
     *
     * Called when a commit fails after its mutations have already been applied to the
     * shared Bundle: a unit of work's commit, or the one-mutation commit that
     * mutateAndSave performs for callers with no unit of work bound. Leaving them there
     * would hand a failed transaction's changes to the next transaction, which - now that
     * transactions are serialised - starts immediately afterwards and would persist them.
     * Ids are not reused, because every nextXxxId() persisted its bumped sequence before
     * the transaction reached commit.
     *
     * @param cause the commit failure, rethrown by the caller
     */
    void discardChanges(Exception cause) {
        lock.lock();
        try {
            loadUnderLock();
        } catch (Exception reloadFailure) {
            cause.addSuppressed(reloadFailure);
            throw new IllegalStateException(
                    "A commit failed and the store at '" + file
                            + "' could not be restored, so it may still hold uncommitted changes.",
                    cause);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Creates a JSON data store backed by the given file path.
     *
     * @param path path to the JSON file
     */
    public JsonDataStore(String path) {
        this.file = new File(path);
        this.om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        File dir = this.file.getParentFile();
        if (dir != null) {
            dir.mkdirs();
        }
    }

    /**
     * Returns the live shared data. The caller must already hold the store lock.
     *
     * This method used to be synchronized, which protected the field read and nothing
     * else: the monitor was released before the caller iterated or mutated the lists it
     * had just been handed. Rather than leave a keyword here that looks like protection,
     * the precondition is now checked, so a missing lock fails at the call site instead
     * of corrupting a list. Prefer read(...) where the whole access fits in one block.
     */
    public Bundle data() {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException(
                    "JsonDataStore.data() requires the store lock - use read(...) or lock()/unlock()");
        }
        return cache;
    }

    /**
     * The two halves of the name a publish gives its working file. They are constants because
     * the writer below and the sweep further down have to agree on them exactly: a sweep that
     * has drifted from the writer either deletes the wrong file or collects nothing.
     */
    private static final String TEMP_PREFIX = "store-";
    private static final String TEMP_SUFFIX = ".json.tmp";

    /**
     * Writes the current in-memory data to disk.
     * Takes the store lock so Jackson cannot iterate a list that another thread is
     * structurally modifying. Inside a unit of work the lock is already held by this
     * thread and the acquisition is a reentrant no-op.
     *
     * The write goes to a sibling temp file which then replaces the store in one move.
     * Serializing straight over the store meant that anything interrupting the write - a crash,
     * a full disk, a killed process - left a truncated document where the data had been, and
     * the whole of it was gone rather than the tail: the old bytes are overwritten from the
     * first one. {@code Bootstrap} was made to refuse to start against exactly that, so the
     * cost was a store that had to be deleted by hand. Now the store is either the previous
     * complete document or the new one, never a prefix of either.
     *
     * Three details make that true rather than nearly true. The temp file is created **in the
     * same directory**, because {@code ATOMIC_MOVE} is only defined within one file store. The
     * bytes are forced to the device before the move, so a power loss cannot land the rename
     * ahead of the data it renames. And a failed write deletes its temp file rather than
     * leaving litter beside the store for the next reader to wonder about. A run that is killed
     * outright never reaches that last part, so what it abandoned is collected at the next load.
     *
     * @throws Exception when saving fails
     */
    public void save() throws Exception {
        lock.lock();
        try {
            Path target = file.toPath().toAbsolutePath();
            Path temp = Files.createTempFile(target.getParent(), TEMP_PREFIX, TEMP_SUFFIX);
            try {
                om.writeValue(temp.toFile(), cache);
                try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                    ch.force(true);
                }
                Files.move(temp, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.deleteIfExists(temp);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    // Initialize sequences with auto-detected max+1 values for backward compatibility
    public void load() throws Exception {
        lock.lock();
        try {
            loadUnderLock();
            // Once per opened store, inside the hold the load already has. Deliberately not in
            // loadUnderLock, which discardChanges also runs, after every failed commit. It runs
            // after the read rather than before it, so a store that cannot be parsed is left
            // with its directory exactly as whoever has to diagnose it will find it.
            sweepAbandonedTempFiles();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Collects temp files that an earlier run abandoned beside the store.
     *
     * save() deletes its own working file when a publish fails, but a run killed between
     * creating that file and completing the move never reaches that catch, and nothing collected
     * the orphan afterwards: the next publish creates a temp file under a fresh name and moves
     * that one instead. They accumulate, in a directory whose whole content is meant to be one
     * document.
     *
     * The match is deliberately narrow - the prefix and suffix a publish uses, the digits
     * createTempFile puts between them, a regular file, and never the store itself. Deleting one
     * file somebody meant to keep is far worse than the litter this removes. That is also why
     * the digits are checked although their shape is not part of createTempFile's contract: a
     * JDK that changed it would leave the litter in place, which is the direction to fail in.
     * And the names are compared rather than globbed because the default glob matcher ignores
     * case on Windows, and a sweep must not be wider on one platform than on another.
     *
     * Nothing is left to age first, and it is worth being exact about what that costs. A working
     * file this store has in flight is unreachable, because save() and the sweep run under the
     * same lock and it is this instance's lock. What that lock does not cover is a publish by
     * someone else in the same directory, and the default layout has one: the console app and the
     * API write storage/data.json while the demo writes storage/demo.json, two legitimate writers
     * of two different documents whose working files are named alike. A second process over the
     * same document cannot collide here any more: JsonStoreGuard turns it away at startup,
     * before its store would list this directory.
     *
     * That is a bounded loss rather than an argument for an age threshold. The most the sweep can
     * take from such a writer is a publish that has not happened yet: the move finds nothing to
     * move and the caller is told its write failed, in the same breath it would be told about a
     * full disk. No document that has been published is ever at risk, because the sweep matches
     * no name a published document has. A threshold would buy a narrower window in exchange for
     * assuming something about timestamp granularity and clock skew on the file store, and would
     * leave every leftover it noticed for some later run to deal with.
     *
     * A leftover that will not go away is left where it is. Opening the store is not the place
     * to refuse work over a file nobody is reading.
     */
    private void sweepAbandonedTempFiles() {
        // Collected first and deleted once the stream is closed: a DirectoryStream does not
        // define what its iterator does when the directory changes underneath it.
        List<Path> leftovers = new ArrayList<>();
        // Resolving the store path is inside the try: File tolerates a name the file system
        // cannot parse - a stray colon on Windows - and loadUnderLock never converts such a path,
        // since it returns early on one that does not exist. That store used to open empty and
        // fail at its first save, and its first save is still where it should find out.
        try {
            Path store = file.toPath().toAbsolutePath();
            Path dir = store.getParent();
            if (dir == null) {
                return;
            }
            try (var entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    Path name = entry.getFileName();
                    if (name != null
                            && isAbandonedTempName(name.toString())
                            && !name.equals(store.getFileName())
                            && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        leftovers.add(entry);
                    }
                }
            }
        } catch (Exception unsweepableDirectory) {
            // A directory this cannot even list is not a reason to keep the store shut, and a
            // listing that broke halfway is not one to act on half of.
            return;
        }
        for (Path leftover : leftovers) {
            try {
                Files.deleteIfExists(leftover);
            } catch (Exception stubbornLeftover) {
                // Something else may hold it open, or the directory may be read only. Either
                // way the store itself was read, and the caller gets the store.
            }
        }
    }

    /**
     * Recognises exactly the names {@link #save()} gives its working file.
     */
    private static boolean isAbandonedTempName(String name) {
        if (name.length() <= TEMP_PREFIX.length() + TEMP_SUFFIX.length()
                || !name.startsWith(TEMP_PREFIX)
                || !name.endsWith(TEMP_SUFFIX)) {
            return false;
        }
        for (int i = TEMP_PREFIX.length(); i < name.length() - TEMP_SUFFIX.length(); i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    // Replaces the cache field wholesale, so it needs the same lock as every reader.
    private void loadUnderLock() throws Exception {
        if (!file.exists() || Files.size(file.toPath()) == 0) {
            cache = new Bundle();
            return;
        }
        cache = om.readValue(file, Bundle.class);
        if (cache.customers == null) cache.customers = new ArrayList<>();
        if (cache.accounts == null) cache.accounts = new ArrayList<>();
        if (cache.transfers == null) cache.transfers = new ArrayList<>();
        if (cache.fraudAlerts == null) cache.fraudAlerts = new ArrayList<>();
        if (cache.fraudAlertNotes == null) cache.fraudAlertNotes = new ArrayList<>();
        if (cache.sequences == null) cache.sequences = new Sequences();

        carryLegacyNotesIntoTheJournal();

        // If a legacy file lacks sequence values, compute safe "next" values.
        //
        // Beneficiaries need their own helper because they are the one entity with no top-level
        // list: they live nested inside each customer. That is why they were missed here, and
        // being missed is not a smaller version of the same problem. The other four sequences
        // recover; this one silently reissued live ids, and Customer.saveBeneficiary replaces a
        // matching id in place rather than refusing it - so adding a payee overwrote an existing
        // one, IBAN, trusted flag and all, and the trusted flag is an input to the fraud rules.
        cache.sequences.customer     = Math.max(cache.sequences.customer,
                nextFromList(cache.customers,       (cz.vsb.minibank.infrastructure.json.dto.JsonCustomer  c) -> c.id, 1));
        cache.sequences.account      = Math.max(cache.sequences.account,
                nextFromList(cache.accounts,        (cz.vsb.minibank.infrastructure.json.dto.JsonAccount   a) -> a.id, 100));
        cache.sequences.beneficiary  = Math.max(cache.sequences.beneficiary,
                nextFromCustomersBeneficiaries(cache.customers, 10));
        cache.sequences.transfer    = Math.max(cache.sequences.transfer,
                nextFromList(cache.transfers,       (cz.vsb.minibank.infrastructure.json.dto.JsonTransfer  t) -> t.id, 5001));
        cache.sequences.fraudAlert  = Math.max(cache.sequences.fraudAlert,
                nextFromList(cache.fraudAlerts,     (cz.vsb.minibank.infrastructure.json.dto.JsonFraudAlert f) -> f.id, 9001));
    }

    /**
     * Turns the single notes text a stored alert used to carry into the first entry of its
     * journal, and clears the field it came from.
     *
     * This backend's half of db/migrate/fraud-alert-comment-and-notes-journal.sql, which does the
     * same thing to the SQL rows with an INSERT and a DROP COLUMN. It runs here, under the load,
     * because this is the one place that sees the whole document: nothing is thrown away and
     * nobody has to remember to run anything.
     *
     * The entry names no author, because the field recorded none and a placeholder would name
     * somebody who never wrote anything, and it is dated at the alert's own creation instant,
     * which is the earliest moment the note could have been written and is what keeps the carried
     * entry at the top of the journal.
     *
     * Idempotent by construction: the text is cleared as it is carried, so a second load finds
     * nothing left to carry, whether or not the store was written back in between.
     */
    private void carryLegacyNotesIntoTheJournal() {
        for (JsonFraudAlert alert : cache.fraudAlerts) {
            if (alert.notes == null || alert.notes.isBlank()) {
                alert.notes = null;
                continue;
            }

            JsonFraudAlertNote carried = new JsonFraudAlertNote();
            carried.alertId = alert.id;
            carried.author = null;
            carried.writtenAt = alert.createdAt;
            carried.text = alert.notes;

            cache.fraudAlertNotes.add(carried);
            alert.notes = null;
        }
    }

    /**
     * Computes "max existing id or defaultStart - 1" plus one for the given list.
     */
    private static <T> int nextFromList(List<T> list, ToIntFunction<T> getId, int defaultStart) {
        int max = defaultStart - 1;
        for (T o : list) {
            int v = getId.applyAsInt(o);
            if (v > max) max = v;
        }
        return max + 1;
    }

    /**
     * Computes next beneficiary id based on all customers.
     */
    private static int nextFromCustomersBeneficiaries(List<JsonCustomer> customers, int defaultStart) {
        int max = defaultStart - 1;
        for (JsonCustomer c : customers) {
            if (c.beneficiaries != null) {
                for (var b : c.beneficiaries) {
                    if (b.id > max) max = b.id;
                }
            }
        }
        return max + 1;
    }

    // Safe save (wrap checked exceptions). save() takes the lock itself.
    private void saveQuiet() {
        try {
            save();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Bumps one sequence and persists it as a single step.
     * The chain nextXxxId -> saveQuiet -> save acquires the store lock three times on
     * one thread; it only works because the lock is reentrant.
     */
    private int nextSequenceValue(ToIntFunction<Sequences> bump) {
        lock.lock();
        try {
            int id = bump.applyAsInt(cache.sequences);
            saveQuiet();
            return id;
        } finally {
            lock.unlock();
        }
    }

    // Centralized ID generators
    public int nextCustomerId()    { return nextSequenceValue(s -> s.customer++); }
    public int nextAccountId()     { return nextSequenceValue(s -> s.account++); }
    public int nextBeneficiaryId() { return nextSequenceValue(s -> s.beneficiary++); }
    public int nextTransferId()    { return nextSequenceValue(s -> s.transfer++); }
    public int nextFraudAlertId()  { return nextSequenceValue(s -> s.fraudAlert++); }
}
