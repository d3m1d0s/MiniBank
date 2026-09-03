package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.domain.customer.Beneficiary;
import cz.vsb.minibank.domain.customer.Customer;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Recovering the beneficiary sequence from a store whose recorded value has fallen behind.
 *
 * Four of the five sequences were recomputed on load and this one was not, because beneficiaries
 * are the one entity with no top-level list - they are nested inside each customer, so they need
 * their own scan and the helper that does it sat unused.
 *
 * The consequence is not a gap in the numbering. {@code Customer.saveBeneficiary} replaces a
 * matching id in place rather than refusing it, so a reissued id meant that adding a payee
 * silently overwrote one the customer already had, taking its IBAN and its trusted flag with it -
 * and the trusted flag decides whether a payment to that payee is held for review.
 */
class BeneficiarySequenceRecoveryTest {

    @TempDir
    Path tempDir;

    private static final String IBAN_A = "CZ6508000000192000145399";
    private static final String IBAN_B = "CZ2001000000000012345678";
    private static final String IBAN_C = "CZ1301000000000098765432";

    @Test
    void aStoreWithNoSequenceBlockAtAllDoesNotReissueLiveBeneficiaryIds() throws IOException {
        Path store = write("""
                {
                  "customers": [
                    { "id": 1, "name": "Alice", "email": "a@example.com",
                      "address": { "street": "Hlavni 1", "city": "Ostrava" },
                      "dailyLimit": 40000.00,
                      "accountIds": [],
                      "beneficiaries": [
                        { "id": 10, "name": "Landlord", "iban": "%s", "trusted": true },
                        { "id": 11, "name": "Utility",  "iban": "%s", "trusted": true },
                        { "id": 12, "name": "Builder",  "iban": "%s", "trusted": false }
                      ] }
                  ],
                  "accounts": [], "transfers": [], "fraudAlerts": []
                }
                """.formatted(IBAN_A, IBAN_B, IBAN_C));

        int next = new Bootstrap(store.toString()).customers.nextBeneficiaryId();

        assertEquals(13, next,
                "the next id must clear every stored beneficiary, not restart at the default 10");
    }

    @Test
    void aStoreWhoseRecordedSequenceHasFallenBehindIsRaisedToClearIt() throws IOException {
        Path store = write("""
                {
                  "customers": [
                    { "id": 1, "name": "Alice", "email": "a@example.com",
                      "address": { "street": "Hlavni 1", "city": "Ostrava" },
                      "dailyLimit": 40000.00,
                      "accountIds": [],
                      "beneficiaries": [
                        { "id": 14, "name": "Landlord", "iban": "%s", "trusted": true },
                        { "id": 15, "name": "Utility",  "iban": "%s", "trusted": true }
                      ] }
                  ],
                  "accounts": [], "transfers": [], "fraudAlerts": [],
                  "sequences": { "customer": 2, "account": 100, "beneficiary": 10,
                                 "transfer": 5001, "fraudAlert": 9001 }
                }
                """.formatted(IBAN_A, IBAN_B));

        int next = new Bootstrap(store.toString()).customers.nextBeneficiaryId();

        assertEquals(16, next, "a recorded value behind the rows must be raised, never trusted");
    }

    /**
     * The same store seen from the customer's side, which is where the damage was. Without the
     * recovery the allocated id is 10, the id the landlord already holds, and adding the new
     * payee replaces the landlord instead of joining him.
     */
    @Test
    void addingAPayeeToARecoveredStoreDoesNotReplaceOneTheCustomerAlreadyHas() throws IOException {
        Path store = write("""
                {
                  "customers": [
                    { "id": 1, "name": "Alice", "email": "a@example.com",
                      "address": { "street": "Hlavni 1", "city": "Ostrava" },
                      "dailyLimit": 40000.00,
                      "accountIds": [],
                      "beneficiaries": [
                        { "id": 10, "name": "Landlord", "iban": "%s", "trusted": true }
                      ] }
                  ],
                  "accounts": [], "transfers": [], "fraudAlerts": []
                }
                """.formatted(IBAN_A));

        Bootstrap infra = new Bootstrap(store.toString());

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            Customer alice = infra.customers.byId(1).orElseThrow();
            alice.saveBeneficiary(new Beneficiary(infra.customers.nextBeneficiaryId(),
                    "Scammer", new IBAN(IBAN_C), false));
            infra.customers.save(alice);
            scope.uow().commit();
        }

        List<Beneficiary> after = new Bootstrap(store.toString())
                .customers.byId(1).orElseThrow().beneficiaries();

        assertEquals(2, after.size(), "the new payee must join the list, not replace a member");

        Beneficiary landlord = after.stream().filter(b -> b.id() == 10).findFirst().orElseThrow();
        assertEquals("Landlord", landlord.name(), "the existing payee must survive untouched");
        assertEquals(IBAN_A, landlord.iban().value());
        assertTrue(landlord.trusted(),
                "and must keep the trusted flag the fraud rules read off it");
    }

    private Path write(String json) throws IOException {
        Path store = tempDir.resolve("data.json");
        Files.writeString(store, json);
        return store;
    }
}
