package cz.vsb.minibank.uow;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.*;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MinibankUowTests {

    Path tempDir;
    String dataPath;
    Bootstrap infra;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("minibank-uow-");
        dataPath = tempDir.resolve("data.json").toString();
        infra = new Bootstrap(dataPath);

        // initial data (outside UoW - can be saved directly)
        int cid = infra.customers.nextId();
        Customer c = new Customer(cid, "Test User", "test@example.com", new Address("Street 1", "City"));
        infra.customers.save(c);

        int accId = infra.accounts.nextId();
        Account a = new Account(accId, new IBAN("CZ6508000000192000145399"), Money.czk(20000), Money.czk(5000));
        infra.accounts.save(a);

        c.addAccountId(accId);
        infra.customers.save(c);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null) {
            // clean up temporary folder
            Files.walk(tempDir)
                    .sorted((p1, p2) -> p2.compareTo(p1))
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }

    @Test
    void identityMap_sameInstanceWithinUow() {
        UnitOfWork uow = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            int accId = infra.accounts.byCustomerId(infra.customers.byId(1).orElseThrow().id())
                    .get(0).id();

            Account a1 = infra.accounts.byId(accId).orElseThrow();
            Account a2 = infra.accounts.byId(accId).orElseThrow();

            assertSame(a1, a2, "Within a single UoW the same Account instance should be returned");
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback(); throw e;
        }
    }

    @Test
    void saveBeneficiary_updatesCacheAndPersistsOnCommit() {
        int customerId = infra.customers.byId(1).orElseThrow().id();

        UnitOfWork uow = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            // load Customer into cache
            Customer cached = infra.customers.byId(customerId).orElseThrow();
            assertTrue(cached.beneficiaries().isEmpty(), "Start with no beneficiaries");

            // save a new beneficiary - goes through repository with UoW
            int bid = infra.customers.nextBeneficiaryId();
            Beneficiary b = new Beneficiary(bid, "Alice", new IBAN("CZ1301000000000098765432"), false);
            infra.customers.saveBeneficiary(customerId, b);

            // aggregate cache should be updated immediately:
            assertEquals(1, cached.beneficiaries().size(), "Customer aggregate cache in UoW should reflect changes");
            assertEquals(bid, cached.beneficiaries().get(0).id());
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback(); throw e;
        }

        // new session/UoW -> read from persistent store
        UnitOfWork uow2 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow2)) {
            Customer reloaded = infra.customers.byId(customerId).orElseThrow();
            assertEquals(1, reloaded.beneficiaries().size(), "After commit, data should be saved in JSON");
            assertEquals("Alice", reloaded.beneficiaries().get(0).name());
            uow2.commit();
        } catch (RuntimeException e) {
            uow2.rollback(); throw e;
        }
    }

    /**
     * A commit applies its buffered mutations to the shared in-memory data before it
     * persists them. If it then fails, those changes must not survive: the store lock is
     * released the moment the commit ends, so the very next transaction would read them
     * and write them to disk - a payment the caller was told had failed.
     */
    @Test
    void aFailedCommitLeavesNothingBehindForTheNextTransaction() {
        int accountId = infra.accounts.byCustomerId(infra.customers.byId(1).orElseThrow().id())
                .get(0).id();

        int doomedTransferId;
        UnitOfWork doomed = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(doomed)) {
            doomedTransferId = infra.transfers.nextId();
            infra.transfers.add(new Transfer(
                    doomedTransferId, accountId, null,
                    "CZ0401000000000000000000", Money.czk(1_000), "CZK"));

            // Fails after the transfer has already been applied to the shared data.
            doomed.registerMutation(() -> { throw new IllegalStateException("commit fails here"); });

            assertThrows(RuntimeException.class, doomed::commit);
        }

        UnitOfWork next = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(next)) {
            assertTrue(infra.transfers.byId(doomedTransferId).isEmpty(),
                    "The failed transaction's transfer must not be visible to the next one");
            assertTrue(infra.transfers.bySourceAccount(accountId).isEmpty(),
                    "The failed transaction must have left the store as it found it");
            next.commit();
        }

        Bootstrap reopened = new Bootstrap(dataPath);
        assertTrue(reopened.transfers.byId(doomedTransferId).isEmpty(),
                "And it must not have reached disk either");
    }

    @Test
    void byCustomerId_usesIdentityMap() {
        int customerId = infra.customers.byId(1).orElseThrow().id();

        UnitOfWork uow = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            // first byId - put account into cache
            int accId = infra.accounts.byCustomerId(customerId).get(0).id();
            Account byId = infra.accounts.byId(accId).orElseThrow();

            // then byCustomerId - should return the same instance
            List<Account> list = infra.accounts.byCustomerId(customerId);
            assertFalse(list.isEmpty());
            Account fromList = list.get(0);

            assertSame(byId, fromList, "byCustomerId should return the same instance from the Identity Map within the UoW");
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback(); throw e;
        }
    }
}
