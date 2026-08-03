package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FeePolicy;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.exceptions.ConflictException;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.NotFoundException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backlog item A3: no money-moving path may act on an object the caller does not own.
 *
 * These go through {@link TransferApplicationService} rather than through the controllers on
 * purpose. The rule lives in the application service because the console UI and DemoRunner
 * call it directly, and a test that only drove HTTP would pass just as happily with the check
 * written into a controller - which is the arrangement that produced this bug.
 *
 * Every case here is one of the escalations that was reproduced against a running API: paying
 * out of a stranger's account, paying a stranger's beneficiary (B17), and authorizing or
 * cancelling a stranger's transfer. Each asserts not only the refusal but that the victim's
 * balance, transfer status, decline reason and remaining OTP attempts are untouched, because
 * a refusal thrown after the damage is done would satisfy the exception assertion alone.
 */
class MoneyPathOwnershipTest {

    private static final int ATTACKER_ID = 2;
    private static final int ATTACKER_ACCOUNT = 101;
    private static final int ATTACKER_BENEFICIARY = 5001;

    private static final int VICTIM_ID = 3;
    private static final int VICTIM_ACCOUNT = 202;
    private static final int VICTIM_BENEFICIARY = 5002;

    /** Belongs to nobody, so it is refused whether or not the guard exists. */
    private static final int UNKNOWN_ACCOUNT = 999_999;
    private static final int UNKNOWN_TRANSFER = 999_999;

    private static final String TARGET_IBAN = "CZ2001000000000012345678";
    private static final Money OPENING_BALANCE = Money.czk(30_000);
    /**
     * The hard ceiling on one day's outflow, matching the demo. theOwnerCanStillUseAllFourMoneyPaths
     * attempts 12 100 in one day and none of it is meant to be refused for the limit, and the
     * 6 000 in setUp would be refused outright at the old 5 000.
     */
    private static final Money DAILY_LIMIT = Money.czk(40_000);

    /** Above the 5 000 authorization threshold, below the 10 000 alert threshold. */
    private static final double WAITING_AMOUNT = 6_000.0;
    /** Below every threshold, so it settles immediately. */
    private static final double SETTLED_AMOUNT = 100.0;

    @TempDir
    Path tempDir;

    private TransferApplicationService service;
    private AccountRepository accounts;
    private TransferRepository transfers;
    private FeePolicy feePolicy;

    private int victimWaitingTransfer;
    private int victimSentTransfer;
    private Money victimBalanceBefore;

    @BeforeEach
    void setUp() {
        Bootstrap infra = new Bootstrap(tempDir.resolve("data.json").toString());
        accounts = infra.accounts;
        transfers = infra.transfers;
        CustomerRepository customers = infra.customers;

        openCustomer(customers, ATTACKER_ID, "Alice Attacker", ATTACKER_ACCOUNT,
                "CZ6508000000192000145399", ATTACKER_BENEFICIARY, "Alice's own payee",
                "CZ2108000000192000145415");
        openCustomer(customers, VICTIM_ID, "Bob Victim", VICTIM_ACCOUNT,
                "CZ4308000000192000145407", VICTIM_BENEFICIARY, "Bob's own payee",
                "CZ9608000000192000145423");

        BootstrapServices services = new BootstrapServices(
                customers, accounts, transfers, infra.alerts, infra.uowFactory);
        service = services.transferService;
        feePolicy = services.feePolicy;

        // The victim's own two transfers, created by the victim, as the attacker would find them.
        victimWaitingTransfer = service.submitPaymentToIban(
                VICTIM_ID, VICTIM_ACCOUNT, TARGET_IBAN, WAITING_AMOUNT, "victim waiting");
        victimSentTransfer = service.submitPaymentToIban(
                VICTIM_ID, VICTIM_ACCOUNT, TARGET_IBAN, SETTLED_AMOUNT, "victim sent");

        assertEquals(TransferStatus.WAITING_AUTH, status(victimWaitingTransfer));
        assertEquals(TransferStatus.SENT, status(victimSentTransfer));

        victimBalanceBefore = balance(VICTIM_ACCOUNT);
    }

    private void openCustomer(CustomerRepository customers, int customerId, String name,
                              int accountId, String accountIban,
                              int beneficiaryId, String beneficiaryName, String beneficiaryIban) {
        Customer c = new Customer(customerId, name, name + "@example.com",
                new Address("Test Street", "Ostrava"));
        c.addAccountId(accountId);
        customers.save(c);
        accounts.save(new Account(accountId, new IBAN(accountIban), OPENING_BALANCE, DAILY_LIMIT));
        customers.saveBeneficiary(customerId,
                new Beneficiary(beneficiaryId, beneficiaryName, new IBAN(beneficiaryIban), true));
    }

    private TransferStatus status(int transferId) {
        return transfers.byId(transferId).orElseThrow().status();
    }

    private Money balance(int accountId) {
        return accounts.byId(accountId).orElseThrow().balance();
    }

    /** Everything about the victim that an escalation would have disturbed. */
    private void assertVictimUntouched() {
        assertEquals(victimBalanceBefore, balance(VICTIM_ACCOUNT),
                "The victim's balance must not move");

        Transfer waiting = transfers.byId(victimWaitingTransfer).orElseThrow();
        assertEquals(TransferStatus.WAITING_AUTH, waiting.status(),
                "The victim's pending transfer must still be pending");
        assertEquals(0, waiting.authAttempts(),
                "A stranger must not be able to spend the victim's OTP attempts");
        assertNull(waiting.declineReason(),
                "A stranger must not be able to write the victim's audit reason");

        assertEquals(TransferStatus.SENT, status(victimSentTransfer));
    }

    // ---------------------------------------------------------------- the reproduced attacks

    /**
     * Reproduced as: alice paid 900 CZK out of customer 3's account 12.
     */
    @Test
    void payingOutOfAnotherCustomersAccountIsRefusedAndMovesNothing() {
        assertThrows(NotFoundException.class, () -> service.submitPaymentToIban(
                ATTACKER_ID, VICTIM_ACCOUNT, TARGET_IBAN, 900.0, "not my account"));

        assertVictimUntouched();
        assertTrue(transfers.bySourceAccount(VICTIM_ACCOUNT).stream()
                        .noneMatch(t -> t.targetIbanSnapshot().equals(TARGET_IBAN)
                                && t.amount().equals(Money.czk(900.0))),
                "No transfer may have been created against the victim's account");
    }

    /**
     * B17: beneficiaryById flat-mapped across every customer, so a beneficiary belonging to
     * somebody else could be paid. The source account on the same call was unchecked too, so
     * both halves are asserted here.
     */
    @Test
    void payingAnotherCustomersBeneficiaryIsRefused() {
        Money attackerBefore = balance(ATTACKER_ACCOUNT);

        assertThrows(NotFoundException.class, () -> service.submitPaymentByBeneficiary(
                ATTACKER_ID, ATTACKER_ACCOUNT, VICTIM_BENEFICIARY, 900.0, "not my payee"));

        assertEquals(attackerBefore, balance(ATTACKER_ACCOUNT),
                "A refused payment must not debit the caller either");
        assertTrue(transfers.bySourceAccount(ATTACKER_ACCOUNT).isEmpty(),
                "No transfer may have been created");
        assertVictimUntouched();
    }

    @Test
    void payingFromAnotherCustomersAccountToOwnBeneficiaryIsRefused() {
        assertThrows(NotFoundException.class, () -> service.submitPaymentByBeneficiary(
                ATTACKER_ID, VICTIM_ACCOUNT, ATTACKER_BENEFICIARY, 900.0, "not my account"));

        assertVictimUntouched();
    }

    /**
     * Reproduced as: alice authorized the victim's transfer 102, moving 6060.00 out of
     * account 12.
     */
    @Test
    void authorizingAnotherCustomersTransferIsRefusedAndSpendsNoAttempt() {
        assertThrows(NotFoundException.class,
                () -> service.authorizePayment(ATTACKER_ID, victimWaitingTransfer, "0000"));

        assertVictimUntouched();
    }

    /**
     * Reproduced as: both attackers cancelled the victim's transfers, writing the falsified
     * audit reason "Canceled by customer".
     */
    @Test
    void cancellingAnotherCustomersTransferIsRefusedAndWritesNoAuditReason() {
        assertThrows(NotFoundException.class,
                () -> service.cancelPayment(ATTACKER_ID, victimWaitingTransfer));

        assertVictimUntouched();
    }

    // ---------------------------------------------------------------- the ordering property

    /**
     * The single thing A3 buys that A4 could not re-derive, and the reason the check is
     * inside requireTransfer rather than after it.
     *
     * A stranger's SENT transfer must answer "not found", not "already sent": a 409 that a
     * non-owner can reach proves the id is real, which is the enumeration oracle the
     * NotFoundException contract exists to remove. Move the check one line down, below the
     * status guard, and this is what turns red.
     */
    @Test
    void theOwnershipCheckPrecedesTheCancelStatusGuard() {
        assertThrows(NotFoundException.class,
                () -> service.cancelPayment(ATTACKER_ID, victimSentTransfer));

        // Same call by the owner does reach the status guard, so the 409 still exists and it
        // is only the stranger who cannot see it.
        assertThrows(ConflictException.class,
                () -> service.cancelPayment(VICTIM_ID, victimSentTransfer));

        assertVictimUntouched();
    }

    /**
     * The same ordering on the authorize path, where reaching the status guard would also
     * have let a stranger burn the victim's three OTP attempts.
     */
    @Test
    void theOwnershipCheckPrecedesTheAuthorizeStatusGuard() {
        assertThrows(NotFoundException.class,
                () -> service.authorizePayment(ATTACKER_ID, victimSentTransfer, "9999"));

        assertThrows(ConflictException.class,
                () -> service.authorizePayment(VICTIM_ID, victimSentTransfer, "9999"));

        assertVictimUntouched();
    }

    // ---------------------------------------------------------------- indistinguishability

    /**
     * A refusal must be worded by the same template as an id that exists nowhere. The wire
     * halves of this pair - identical status and identical body bytes - are in
     * HttpErrorContractTest; here it is the message template that is pinned.
     */
    @Test
    void aStrangersIdIsRefusedLikeAnIdThatDoesNotExist() {
        NotFoundException unknownAccount = assertThrows(NotFoundException.class,
                () -> service.submitPaymentToIban(ATTACKER_ID, UNKNOWN_ACCOUNT, TARGET_IBAN, 900.0, ""));
        NotFoundException strangersAccount = assertThrows(NotFoundException.class,
                () -> service.submitPaymentToIban(ATTACKER_ID, VICTIM_ACCOUNT, TARGET_IBAN, 900.0, ""));

        assertEquals("Account not found: " + UNKNOWN_ACCOUNT, unknownAccount.getMessage());
        assertEquals("Account not found: " + VICTIM_ACCOUNT, strangersAccount.getMessage());

        NotFoundException unknownTransfer = assertThrows(NotFoundException.class,
                () -> service.cancelPayment(ATTACKER_ID, UNKNOWN_TRANSFER));
        NotFoundException strangersTransfer = assertThrows(NotFoundException.class,
                () -> service.cancelPayment(ATTACKER_ID, victimWaitingTransfer));

        assertEquals("Transfer not found: " + UNKNOWN_TRANSFER, unknownTransfer.getMessage());
        assertEquals("Transfer not found: " + victimWaitingTransfer, strangersTransfer.getMessage());
    }

    /**
     * A session naming a customer the store no longer holds is not an ownership denial: the
     * caller never named that id, so it must not be reported to them as a missing item.
     */
    @Test
    void aSessionNamingAMissingCustomerIsAServerFault() {
        assertThrows(DataIntegrityException.class, () -> service.submitPaymentToIban(
                404, ATTACKER_ACCOUNT, TARGET_IBAN, 900.0, ""));
        assertThrows(DataIntegrityException.class,
                () -> service.cancelPayment(404, victimWaitingTransfer));
    }

    // ---------------------------------------------------------------- the owner still works

    /**
     * Guards against over-tightening. All four money paths must still work end to end for the
     * customer who owns the objects, including the debit and the fee.
     */
    @Test
    void theOwnerCanStillUseAllFourMoneyPaths() {
        Money start = balance(ATTACKER_ACCOUNT);

        // UC 04 -> UC 05: pay to an IBAN, authorize, and see the money leave.
        int authorized = service.submitPaymentToIban(
                ATTACKER_ID, ATTACKER_ACCOUNT, TARGET_IBAN, WAITING_AMOUNT, "mine");
        assertEquals(TransferStatus.WAITING_AUTH, status(authorized));

        service.authorizePayment(ATTACKER_ID, authorized, "0000");
        assertEquals(TransferStatus.SENT, status(authorized));

        Money amount = Money.czk(WAITING_AMOUNT);
        Money expected = start.minus(amount.plus(feePolicy.compute(amount)));
        assertEquals(expected, balance(ATTACKER_ACCOUNT),
                "The owner's payment must debit amount plus fee");

        // UC 04 by beneficiary, from the caller's own address book.
        int byBeneficiary = service.submitPaymentByBeneficiary(
                ATTACKER_ID, ATTACKER_ACCOUNT, ATTACKER_BENEFICIARY, SETTLED_AMOUNT, "mine too");
        assertEquals(TransferStatus.SENT, status(byBeneficiary),
                "A trusted beneficiary below the thresholds settles immediately");

        // UC 19: cancel a pending one of the caller's own.
        int toCancel = service.submitPaymentToIban(
                ATTACKER_ID, ATTACKER_ACCOUNT, TARGET_IBAN, WAITING_AMOUNT, "cancel me");

        // Held, not waiting, and that is the cumulative alert rule working rather than a
        // regression: this is the second payment of WAITING_AMOUNT to TARGET_IBAN today from
        // this account, the first has already settled, and together they pass the alert
        // threshold. The owner's third money path now goes through the fraud desk. Asserted
        // rather than left implicit, because nothing else here would notice - cancelPayment
        // guards only SENT, so the DECLINED below holds either way and the change would be
        // silent.
        assertEquals(TransferStatus.HELD_FOR_REVIEW, status(toCancel),
                "a split above the threshold to one payee is held, even when it is the owner's");

        // And a held payment is still the owner's to withdraw, which is the point of the path.
        service.cancelPayment(ATTACKER_ID, toCancel);
        assertEquals(TransferStatus.DECLINED, status(toCancel));

        assertVictimUntouched();
    }
}
