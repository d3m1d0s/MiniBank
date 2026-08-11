package cz.vsb.minibank.infrastructure.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import cz.vsb.minibank.infrastructure.json.dto.JsonAccount;
import cz.vsb.minibank.infrastructure.json.dto.JsonCustomer;
import cz.vsb.minibank.infrastructure.json.dto.JsonFraudAlert;
import cz.vsb.minibank.infrastructure.json.dto.JsonTransfer;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
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
        // Sequence section
        public Sequences sequences = new Sequences();
    }

    private Bundle cache = new Bundle();

    /**
     * The one lock for the whole store. It guards the cache field, the four lists inside
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
     * inside it - including AppLogger's audit writes to stderr and minibank.log, and
     * PaymentNetworkGateway.send. Nothing invoked between begin() and commit() may
     * block indefinitely, or it blocks every other thread that touches the store.
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
     * leaving litter beside the store for the next reader to wonder about.
     *
     * @throws Exception when saving fails
     */
    public void save() throws Exception {
        lock.lock();
        try {
            Path target = file.toPath().toAbsolutePath();
            Path temp = Files.createTempFile(target.getParent(), "store-", ".json.tmp");
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
        } finally {
            lock.unlock();
        }
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
        if (cache.sequences == null) cache.sequences = new Sequences();

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
