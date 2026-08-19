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
    void seedCreatesTheCustomerWithTwoAccountsAndThreeBeneficiaries() {
        int customerId = scenario.seed();

        Customer customer = infra.customers.byId(customerId).orElseThrow();
        assertEquals(2, customer.accountIds().size());
        assertEquals(3, customer.beneficiaries().size());
        assertTrue(customer.beneficiaries().stream().anyMatch(Beneficiary::trusted));
        assertTrue(customer.beneficiaries().stream().anyMatch(b -> !b.trusted()));
        // The third payee is the customer's own second account, and it is the only one whose IBAN
        // this bank holds. Without it no seeded payment stays inside the bank, so the credit leg
        // is never taken and every screen that says where the money went has one answer for every
        // row it can draw.
        assertTrue(
                customer.beneficiaries().stream()
                        .anyMatch(b -> b.iban().equals(DemoScenario.SECONDARY_IBAN)),
                "One payee must be an account this bank holds");
    }

    @Test
    void theSettledTransferDebitsTheAccountUsingTheCurrentFeePolicy() {
        scenario.seed();

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();

        // Two settled payments of the same amount leave this account: one to a foreign IBAN and
        // one to the customer's own second account. Both are debited the same way, and the second
        // one is also credited somewhere this bank can see, which the next test checks.
        Money amount = Money.czk(1_500);
        Money charge = amount.plus(feePolicy.compute(amount));
        Money expected = Money.czk(25_000).minus(charge).minus(charge);
        assertEquals(expected, primary.balance(),
                "The opening balance must be reduced by each settled amount plus its computed fee");
    }

    @Test
    void seedProducesTwoSettledAndOnePendingTransferPlusAnAlert() {
        scenario.seed();

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        List<Transfer> history = infra.transfers.bySourceAccount(primary.id());

        assertEquals(3, history.size());
        assertEquals(2, history.stream().filter(t -> t.status() == TransferStatus.SENT).count());
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
    void theSecondaryAccountIsCreditedByThePaymentThatStaysInTheBank() {
        scenario.seed();

        Account secondary = infra.accounts.byIban(DemoScenario.SECONDARY_IBAN).orElseThrow();
        // The credit leg, and the only place in the seeded data where it is taken. The amount
        // arrives whole: a fee is charged to the account that pays, not to the one that is paid.
        assertEquals(Money.czk(5_000).plus(Money.czk(1_500)), secondary.balance(),
                "A payment to an account this bank holds must be credited in the same unit of "
                        + "work as the debit, rather than handed to the network");
    }

    @Test
    void thePaymentThatStaysInTheBankOwesTheNetworkNothing() {
        scenario.seed();

        Account primary = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        Account secondary = infra.accounts.byIban(DemoScenario.SECONDARY_IBAN).orElseThrow();

        // The dispatch state is what separates the two settled payments, and it is the field the
        // history screens read to say whether the money left. Reading an absent one as "stayed
        // here" is what the screens must not do: absent also means a row older than that column.
        Transfer internal = infra.transfers.bySourceAccount(primary.id()).stream()
                .filter(t -> t.status() == TransferStatus.SENT)
                .filter(t -> t.targetIbanSnapshot().equals(secondary.iban().value()))
                .findFirst().orElseThrow();
        Transfer external = infra.transfers.bySourceAccount(primary.id()).stream()
                .filter(t -> t.status() == TransferStatus.SENT)
                .filter(t -> !t.targetIbanSnapshot().equals(secondary.iban().value()))
                .findFirst().orElseThrow();

        assertNull(internal.dispatchState(), "An intra-bank payment reaches no gateway");
        assertEquals(DispatchState.PENDING, external.dispatchState(),
                "A payment that leaves the bank owes the network a dispatch");
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
        assertEquals(3, transfers);
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
