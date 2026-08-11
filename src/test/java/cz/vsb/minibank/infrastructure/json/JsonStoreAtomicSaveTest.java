package cz.vsb.minibank.infrastructure.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.vsb.minibank.infrastructure.json.dto.JsonCustomer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the JSON store publishes a new document.
 *
 * It used to serialize straight over the file it was replacing, so anything that interrupted the
 * write - a crash, a full disk, a killed process - left a truncated document where the data had
 * been. Not the tail of it: the old bytes are overwritten from the first one, so the previous
 * complete document was gone as soon as the new write began.
 *
 * The write now goes to a sibling temp file and replaces the store in one move. What can be
 * asserted here is the observable half of that: the temp file is always consumed, and a publish
 * that cannot complete leaves nothing behind, neither a working file beside the store nor an
 * unpublished change in the store's memory. The other half - that a reader never sees a prefix
 * - is a property of {@code ATOMIC_MOVE} rather than of this code, and pinning it would need a
 * deliberately failing serializer injected into the store, which is production surface added for
 * one test.
 */
class JsonStoreAtomicSaveTest {

    @TempDir
    Path tempDir;

    @Test
    void aSaveLeavesTheStoreAndNothingElseBesideIt() throws Exception {
        Path store = tempDir.resolve("data.json");
        JsonDataStore s = new JsonDataStore(store.toString());

        s.load();
        s.save();
        s.save();
        s.save();

        assertEquals(List.of("data.json"), namesIn(tempDir),
                "the temp file is a step, not a residue");
    }

    @Test
    void whatTheStoreHoldsAfterASaveIsAWholeDocument() throws Exception {
        Path store = tempDir.resolve("data.json");
        JsonDataStore s = new JsonDataStore(store.toString());
        s.load();

        s.lock();
        try {
            JsonCustomer c = new JsonCustomer();
            c.id = 1;
            c.name = "Alice";
            s.data().customers.add(c);
        } finally {
            s.unlock();
        }
        s.save();

        assertDoesNotThrow(() -> new ObjectMapper().readTree(store.toFile()),
                "the published document must parse");

        JsonDataStore reopened = new JsonDataStore(store.toString());
        reopened.load();
        reopened.lock();
        try {
            assertEquals(1, reopened.data().customers.size());
            assertEquals("Alice", reopened.data().customers.get(0).name);
        } finally {
            reopened.unlock();
        }
    }

    /**
     * A publish that cannot complete must not leave its working file behind for the next reader
     * to find. The store path is made a directory here because that is the one way to refuse the
     * replacement on every platform: a file cannot be renamed over a directory anywhere.
     */
    @Test
    void aPublishThatCannotCompleteCleansUpAfterItself() throws Exception {
        Path store = tempDir.resolve("data.json");
        Files.createDirectories(store);

        JsonDataStore s = new JsonDataStore(store.toString());
        s.load();

        assertThrows(Exception.class, s::save, "renaming a file over a directory must fail");

        assertEquals(List.of("data.json"), namesIn(tempDir),
                "a failed publish must delete its temp file rather than leave it beside the store");
    }

    /**
     * A change whose publish failed must not stay in memory waiting for the next publish to
     * carry it out. The path with no unit of work bound applies its mutation straight to the
     * shared data and then saves, so a refused save left the change in the cache and the next
     * successful save wrote it to disk. That next save need not be a deliberate one: every
     * nextXxxId() persists the whole cache in order to bump one sequence, so allocating an id
     * was enough to publish an operation that had already reported failure.
     *
     * The save is refused here by removing the directory the store lives in, because a publish
     * writes its temp file beside the store. That fails on every platform, it leaves the store
     * as something the revert can still read back rather than something it chokes on, and it
     * can be undone, so the test can then watch what a successful save actually puts on disk.
     */
    @Test
    void aChangeWhosePublishFailedIsNotCarriedIntoTheNextOne() throws Exception {
        Path vault = tempDir.resolve("vault");
        Path store = vault.resolve("data.json");

        JsonDataStore s = new JsonDataStore(store.toString());
        s.load();
        Files.delete(vault);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> s.mutateAndSave(() -> {
                    JsonCustomer c = new JsonCustomer();
                    c.id = 1;
                    c.name = "Alice";
                    s.data().customers.add(c);
                }),
                "a publish with nowhere to write its temp file must fail");
        assertEquals(RuntimeException.class, failure.getClass(),
                "reverting must not change what the caller is told about the failure");

        assertEquals(List.of(), customerNamesIn(s),
                "the refused change must be gone from the store, not sitting in it");

        Files.createDirectories(vault);
        s.save();

        JsonDataStore reopened = new JsonDataStore(store.toString());
        reopened.load();

        assertEquals(List.of(), customerNamesIn(reopened),
                "a later save publishes what the store holds, and the refused change is not it");
    }

    private static List<String> customerNamesIn(JsonDataStore store) {
        return store.read(bundle -> bundle.customers.stream().map(c -> c.name).toList());
    }

    private static List<String> namesIn(Path dir) throws Exception {
        try (var entries = Files.list(dir)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
