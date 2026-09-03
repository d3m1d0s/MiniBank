package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.domain.transfer.Transfer;
import cz.vsb.minibank.domain.transfer.TransferStatus;
import cz.vsb.minibank.infrastructure.json.dto.JsonTransfer;
import cz.vsb.minibank.infrastructure.json.repo.JsonTransferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What one page of a customer's payments looks like on the backend that has no database to cut it.
 *
 * The SQL adapter states the order and the bound in a statement, so the store enforces them. This
 * one filters and sorts a list in memory and then slices it, which is the only thing it can do with
 * a single JSON document, and that is precisely why the properties below are worth pinning here:
 * every one of them is a promise the interface makes and that nothing but this code keeps.
 *
 * The order is created_at descending with the id descending behind it. The tie-break is not
 * decoration - two rows carrying the same instant leave the sort undecided, and an undecided sort
 * under offset paging shows one of them on both pages and the other on neither.
 */
class JsonTransferPagingTest {

    @TempDir
    Path tempDir;

    private static final int ACCOUNT = 100;
    private static final int OTHER_ACCOUNT = 200;
    private static final int STRANGERS_ACCOUNT = 300;
    private static final String PAYEE = "CZ6508000000192000145399";

    private static final Set<TransferStatus> EVERY_STATUS = Set.of();
    private static final Set<TransferStatus> STILL_OPEN =
            Set.of(TransferStatus.WAITING_AUTH, TransferStatus.HELD_FOR_REVIEW);

    @Test
    void thePageIsNewestFirstAcrossEveryAccountTheCustomerHolds() throws Exception {
        JsonTransferRepository transfers = repositoryOver(
                row(1, ACCOUNT, "SENT", "2026-03-01T10:00:00Z"),
                row(2, OTHER_ACCOUNT, "SENT", "2026-03-04T10:00:00Z"),
                row(3, ACCOUNT, "DECLINED", "2026-03-02T10:00:00Z"),
                row(4, OTHER_ACCOUNT, "WAITING_AUTH", "2026-03-03T10:00:00Z"));

        assertEquals(List.of(2, 4, 3, 1),
                ids(transfers.bySourceAccountsNewestFirst(
                        List.of(ACCOUNT, OTHER_ACCOUNT), EVERY_STATUS, 0, 10)),
                "one list across the accounts, ordered by when the payment was made");
    }

    @Test
    void aStrangersPaymentIsNotInIt() throws Exception {
        JsonTransferRepository transfers = repositoryOver(
                row(1, ACCOUNT, "SENT", "2026-03-01T10:00:00Z"),
                row(2, STRANGERS_ACCOUNT, "SENT", "2026-03-04T10:00:00Z"));

        assertEquals(List.of(1),
                ids(transfers.bySourceAccountsNewestFirst(List.of(ACCOUNT), EVERY_STATUS, 0, 10)));
        assertEquals(1, transfers.countBySourceAccounts(List.of(ACCOUNT), EVERY_STATUS));
    }

    /**
     * The property offset paging lives or dies on. Every row is seen exactly once across the
     * pages, and the two rows sharing an instant do not swap places between them.
     */
    @Test
    void consecutivePagesCoverEveryRowOnceEvenWhenTwoShareAnInstant() throws Exception {
        String sameInstant = "2026-03-02T10:00:00Z";
        JsonTransferRepository transfers = repositoryOver(
                row(1, ACCOUNT, "SENT", "2026-03-01T10:00:00Z"),
                row(2, ACCOUNT, "SENT", sameInstant),
                row(3, ACCOUNT, "SENT", sameInstant),
                row(4, ACCOUNT, "SENT", "2026-03-03T10:00:00Z"),
                row(5, ACCOUNT, "SENT", "2026-03-04T10:00:00Z"));

        assertEquals(List.of(5, 4),
                ids(transfers.bySourceAccountsNewestFirst(List.of(ACCOUNT), EVERY_STATUS, 0, 2)));
        assertEquals(List.of(3, 2),
                ids(transfers.bySourceAccountsNewestFirst(List.of(ACCOUNT), EVERY_STATUS, 2, 2)),
                "the tie is broken by the id, descending, and broken the same way every read");
        assertEquals(List.of(1),
                ids(transfers.bySourceAccountsNewestFirst(List.of(ACCOUNT), EVERY_STATUS, 4, 2)),
                "the last page is short rather than padded");
        assertEquals(List.of(),
                ids(transfers.bySourceAccountsNewestFirst(List.of(ACCOUNT), EVERY_STATUS, 6, 2)),
                "and a page past the end is empty rather than a refusal");
    }

    /**
     * The count is what the foot of the list prints beside the rows, so it counts the whole set and
     * not the page. It also has to answer the page query's own predicate: a total taken over a
     * wider set than the list is a Show more button that survives the last page.
     */
    @Test
    void theTotalCountsTheWholeListAndNotThePage() throws Exception {
        JsonTransferRepository transfers = repositoryOver(
                row(1, ACCOUNT, "SENT", "2026-03-01T10:00:00Z"),
                row(2, ACCOUNT, "SENT", "2026-03-02T10:00:00Z"),
                row(3, ACCOUNT, "HELD_FOR_REVIEW", "2026-03-03T10:00:00Z"),
                row(4, ACCOUNT, "WAITING_AUTH", "2026-03-04T10:00:00Z"));

        assertEquals(4, transfers.countBySourceAccounts(List.of(ACCOUNT), EVERY_STATUS));
        assertEquals(2, transfers.countBySourceAccounts(List.of(ACCOUNT), STILL_OPEN),
                "narrowed by the same statuses the page it describes is narrowed by");
    }

    @Test
    void theWaitingListSeesOnlyThePaymentsItCanActOn() throws Exception {
        JsonTransferRepository transfers = repositoryOver(
                row(1, ACCOUNT, "SENT", "2026-03-01T10:00:00Z"),
                row(2, ACCOUNT, "DECLINED", "2026-03-02T10:00:00Z"),
                row(3, ACCOUNT, "HELD_FOR_REVIEW", "2026-03-03T10:00:00Z"),
                row(4, ACCOUNT, "WAITING_AUTH", "2026-03-04T10:00:00Z"));

        assertEquals(List.of(4, 3),
                ids(transfers.bySourceAccountsNewestFirst(List.of(ACCOUNT), STILL_OPEN, 0, 10)),
                "a held payment belongs in the waiting list; a settled or withdrawn one does not");
    }

    /**
     * Two ways of asking for nothing, and neither of them is an error. A customer with no accounts
     * is a real state of the store, and a caller asking for no rows has asked a question with an
     * answer.
     */
    @Test
    void noAccountsAndNoRowsAskedForAreBothTheEmptyPage() throws Exception {
        JsonTransferRepository transfers = repositoryOver(
                row(1, ACCOUNT, "SENT", "2026-03-01T10:00:00Z"));

        assertTrue(transfers.bySourceAccountsNewestFirst(List.of(), EVERY_STATUS, 0, 10).isEmpty());
        assertEquals(0, transfers.countBySourceAccounts(List.of(), EVERY_STATUS));
        assertTrue(transfers.bySourceAccountsNewestFirst(List.of(ACCOUNT), EVERY_STATUS, 0, 0)
                .isEmpty());
    }

    private static List<Integer> ids(List<Transfer> page) {
        return page.stream().map(Transfer::id).toList();
    }

    private JsonTransferRepository repositoryOver(JsonTransfer... rows) throws Exception {
        JsonDataStore store = new JsonDataStore(tempDir.resolve("data.json").toString());
        store.load();

        store.lock();
        try {
            for (JsonTransfer row : rows) {
                store.data().transfers.add(row);
            }
        } finally {
            store.unlock();
        }

        return new JsonTransferRepository(store);
    }

    /**
     * A row of the kind the application itself writes, so that each test differs from a loadable
     * row only in the fields it is about. Added in an order that is not the order any test expects
     * back, so a method that simply returned the store's list would fail rather than pass.
     */
    private static JsonTransfer row(int id, int accountId, String status, String createdAt) {
        JsonTransfer dto = new JsonTransfer();
        dto.id = id;
        dto.sourceAccountId = accountId;
        dto.targetIbanSnapshot = PAYEE;
        dto.amount = new BigDecimal("1000.00");
        dto.currency = "CZK";
        dto.status = status;
        dto.createdAt = createdAt;
        return dto;
    }
}
