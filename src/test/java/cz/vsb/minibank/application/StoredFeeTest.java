package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.customer.Account;
import cz.vsb.minibank.domain.customer.Address;
import cz.vsb.minibank.domain.customer.Beneficiary;
import cz.vsb.minibank.domain.customer.Customer;
import cz.vsb.minibank.domain.fee.FeePolicy;
import cz.vsb.minibank.domain.fraud.RuleBasedRiskService;
import cz.vsb.minibank.domain.fee.SimpleFeePolicy;
import cz.vsb.minibank.domain.transfer.Transfer;
import cz.vsb.minibank.domain.transfer.TransferStatus;
import cz.vsb.minibank.domain.fee.ZeroFeePolicy;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import cz.vsb.minibank.application.auth.FixedOtpValidator;
import cz.vsb.minibank.application.config.BootstrapServices;
import cz.vsb.minibank.application.payment.FakePaymentNetworkGateway;
import cz.vsb.minibank.application.payment.TransferApplicationService;

/**
 * A settled transfer keeps the fee it was charged.
 *
 * What this replaces recomputed the fee on every display from whichever FeePolicy bean happened
 * to be wired, so a settled payment's charge was a function of today's configuration. Swap the
 * policy and last month's receipts silently restate themselves, and every historical balance
 * becomes unexplainable by the numbers shown next to it - the balance moved by the old fee, the
 * screen shows the new one.
 *
 * The test is written to fail against that arrangement rather than merely to describe this one:
 * it settles under one policy, then reads the same stored transfer through a service wired with
 * a different policy, and asserts the two answers differ in exactly the way that proves the
 * number came from the store.
 */
class StoredFeeTest {

    private static final int CUSTOMER_ID = 1;
    private static final int ACCOUNT_ID = 100;
    private static final int TRUSTED_BENEFICIARY_ID = 5001;

    private static final String ACCOUNT_IBAN = "CZ6508000000192000145399";
    private static final String TRUSTED_IBAN = "CZ2108000000192000145415";

    private static final Money OPENING = Money.czk(500_000);

    /** 1% under SimpleFeePolicy, and 0.00 under ZeroFeePolicy: a gap no rounding can hide. */
    private static final double AMOUNT = 4_000;

    @TempDir
    Path tempDir;

    private Bootstrap infra;

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());

        Customer customer = new Customer(CUSTOMER_ID, "Fee Probe", "fee@example.com",
                new Address("Hlavni 1", "Ostrava"), Money.czk(400_000));
        customer.addAccountId(ACCOUNT_ID);
        infra.customers.save(customer);
        infra.accounts.save(new Account(ACCOUNT_ID, new IBAN(ACCOUNT_IBAN), OPENING));
        infra.customers.saveBeneficiary(CUSTOMER_ID, new Beneficiary(
                TRUSTED_BENEFICIARY_ID, "Trusted payee", new IBAN(TRUSTED_IBAN), true));
    }

    /**
     * Settle under one policy, then ask the transfer what it was charged while a different
     * policy is in force.
     *
     * The balance is the control: it moved by the fee that was actually taken, so any answer
     * that disagrees with it is a lie whatever else it is consistent with.
     */
    @Test
    void aSettledTransfersFeeSurvivesTheFeePolicyBeingSwapped() {
        FeePolicy atSettlement = new SimpleFeePolicy();
        Money charged = atSettlement.compute(Money.czk(AMOUNT));

        int id = serviceWith(atSettlement).submitPaymentByBeneficiary(
                CUSTOMER_ID, ACCOUNT_ID, TRUSTED_BENEFICIARY_ID, AMOUNT, "under the old policy").transferId();

        Transfer settled = infra.transfers.byId(id).orElseThrow();
        assertEquals(TransferStatus.SENT, settled.status());
        assertEquals(charged, settled.fee(), "the fee that was debited is the fee that is kept");
        assertEquals(OPENING.minus(Money.czk(AMOUNT)).minus(charged), balance(),
                "and the balance moved by exactly that number");

        // The bank changes its fee policy. Nothing about the settled payment changed.
        FeePolicy today = new ZeroFeePolicy();
        assertNotEquals(charged, today.compute(Money.czk(AMOUNT)),
                "this case is only meaningful while the two policies disagree");

        Transfer reloaded = infra.transfers.byId(id).orElseThrow();
        assertEquals(charged, reloaded.feeFor(today),
                "what a customer was charged is not a function of what the bank charges now");
        assertEquals(charged, reloaded.fee(),
                "and it survived the store: this instance was read back off disk");

        // The account still reconciles: balance = opening - amount - the fee on record.
        assertEquals(balance(),
                OPENING.minus(reloaded.amount()).minus(reloaded.feeFor(today)),
                "the fee shown must explain the balance; recomputing it is what broke that");
    }

    /**
     * The fallback, and why it is not a second source of truth.
     *
     * A transfer that has not settled has been charged nothing, so there is no stored number to
     * show and a quote from the current policy is the only honest answer. Rows written before
     * the stored fee are in the same position and get the same treatment.
     */
    @Test
    void anUnsettledTransferQuotesTheCurrentPolicyBecauseItHasBeenChargedNothing() {
        // 30 000 is over the bank-wide soft threshold, so it waits for the customer's code.
        int id = serviceWith(new SimpleFeePolicy()).submitPaymentByBeneficiary(
                CUSTOMER_ID, ACCOUNT_ID, TRUSTED_BENEFICIARY_ID, 30_000, "not yet").transferId();

        Transfer waiting = infra.transfers.byId(id).orElseThrow();
        assertEquals(TransferStatus.WAITING_AUTH, waiting.status());
        assertNull(waiting.fee(), "nothing has been charged, which is not the same as zero");
        assertNull(waiting.settledAt());

        assertEquals(new SimpleFeePolicy().compute(Money.czk(30_000)), waiting.feeFor(new SimpleFeePolicy()));
        assertEquals(Money.czk(0), waiting.feeFor(new ZeroFeePolicy()),
                "an unsettled transfer tracks the live policy, because that is what it would pay");

        assertEquals(OPENING, balance(), "and nothing has moved");
    }

    private TransferApplicationService serviceWith(FeePolicy policy) {
        return new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                policy,
                new RuleBasedRiskService(),
                new FixedOtpValidator(),
                new FakePaymentNetworkGateway(),
                infra.uowFactory
        ).transferService;
    }

    private Money balance() {
        return infra.accounts.byId(ACCOUNT_ID).orElseThrow().balance();
    }
}
