package cz.vsb.minibank.demo;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that DemoScenario produces the intended dataset and that seeding
 * twice changes nothing.
 */
class DemoScenarioTest {

    @TempDir
    Path tempDir;

    private Bootstrap infra;
    private DemoScenario scenario;
    private final FeePolicy feePolicy = new SimpleFeePolicy();

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());
        scenario = new DemoScenario(
                infra.customers, infra.accounts, infra.transfers, infra.alerts,
                infra.uowFactory, feePolicy);
    }

    @Test
    void seedCreatesTheCustomerWithTwoAccountsAndTwoBeneficiaries() {
        int customerId = scenario.seed();

        Customer customer = infra.customers.byId(customerId).orElseThrow();
        assertEquals(2, customer.accountIds().size());
        assertEquals(2, customer.beneficiaries().size());
        assertTrue(customer.beneficiaries().stream().anyMatch(Beneficiary::trusted));
        assertTrue(customer.beneficiaries().stream().anyMatch(b -> !b.trusted()));
    }

    @Test
    void theSettledTransferDebitsTheAccountUsingTheCurrentFeePolicy() {
        scenario.seed();

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();

        Money amount = Money.czk(1_500);
        Money expected = Money.czk(25_000).minus(amount.plus(feePolicy.compute(amount)));
        assertEquals(expected, primary.balance(),
                "The opening balance must be reduced by the settled amount plus the computed fee");
    }

    @Test
    void seedProducesOneSettledAndOnePendingTransferPlusAnAlert() {
        scenario.seed();

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        List<Transfer> history = infra.transfers.bySourceAccount(primary.id());

        assertEquals(2, history.size());
        assertEquals(1, history.stream().filter(t -> t.status() == TransferStatus.SENT).count());
        // Held, not waiting. A seeded alert on a transfer the customer could confirm at will
        // would be exactly the shape the review gate exists to make impossible.
        assertEquals(1, history.stream().filter(t -> t.status() == TransferStatus.HELD_FOR_REVIEW).count());

        List<FraudAlert> queue = infra.alerts.all();
        assertEquals(1, queue.size());
        assertEquals(FraudAlertState.NEW, queue.get(0).state());

        Transfer flagged = history.stream()
                .filter(t -> t.status() == TransferStatus.HELD_FOR_REVIEW)
                .findFirst().orElseThrow();
        assertEquals(flagged.id(), queue.get(0).transferId(),
                "The alert must point at the held transfer");
        assertNull(flagged.authValidUntil(),
                "A held transfer has no authorization window: the five minutes must not run "
                        + "out while the alert sits in the analyst's queue");
    }

    @Test
    void theSecondaryAccountIsUntouched() {
        scenario.seed();

        Account secondary = infra.accounts.byIban(DemoScenario.SECONDARY_IBAN).orElseThrow();
        assertEquals(Money.czk(5_000), secondary.balance());
    }

    @Test
    void seedingTwiceIsANoOpAndReturnsTheSameCustomer() {
        int first = scenario.seed();
        Account afterFirst = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();

        int second = scenario.seed();

        assertEquals(first, second, "The second seed must return the same customer id");

        Account afterSecond = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        assertEquals(afterFirst.balance(), afterSecond.balance(), "Balances must not move");
        // data() now requires the store lock; read(...) is the supported way in.
        int customers = infra.store.read(data -> data.customers.size());
        int accounts = infra.store.read(data -> data.accounts.size());
        int transfers = infra.store.read(data -> data.transfers.size());
        int alerts = infra.store.read(data -> data.fraudAlerts.size());

        assertEquals(1, customers);
        assertEquals(2, accounts);
        assertEquals(2, transfers);
        assertEquals(1, alerts);
    }

    @Test
    void seedingSurvivesAProcessRestartAgainstTheSameStore() {
        int first = scenario.seed();

        Bootstrap reopened = new Bootstrap(tempDir.resolve("data.json").toString());
        DemoScenario again = new DemoScenario(
                reopened.customers, reopened.accounts, reopened.transfers, reopened.alerts,
                reopened.uowFactory, feePolicy);

        assertEquals(first, again.seed());

        int customers = reopened.store.read(data -> data.customers.size());
        assertEquals(1, customers);
    }

    @Test
    void theOwnerOfTheDemoAccountIsResolvable() {
        int customerId = scenario.seed();

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        Customer owner = infra.customers.byAccountId(primary.id()).orElseThrow();

        assertEquals(customerId, owner.id());
    }
}
