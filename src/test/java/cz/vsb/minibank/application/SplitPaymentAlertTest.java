package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.exceptions.TransferUnderReviewException;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An amount split across two payments to one new payee is still caught.
 *
 * The alert rule used to key on a single payment's amount, so 13 000 sent as two payments of
 * 6 500 to the same untrusted IBAN raised nothing and was never held - each half is below the
 * 10 000 threshold and nothing ever added them up. The review gate could not close that gap,
 * because no alert was ever raised for it to gate on.
 *
 * Both orderings are driven here, and they are not the same test. Splitting is only worth doing
 * in the second one: it is the ordering an attacker controls, and it is the ordering the rule
 * cannot catch at creation, because at the moment the second payment is made the first has not
 * settled and is therefore in no total.
 */
class SplitPaymentAlertTest {

    /** Two of these total 13 000, and each on its own is under the 10 000 alert threshold. */
    private static final double HALF = 6_500;

    /** Above the untrusted-and-high authorization threshold, so each half waits for a code. */
    private static final String OTP = "0000";

    private static final IBAN PAYER = new IBAN("CZ6508000000192000145399");
    private static final String PAYEE = "CZ2001000000000012345678";
    private static final String OTHER_PAYEE = "CZ9608000000192000142222";

    @TempDir
    Path tempDir;

    private Bootstrap infra;
    private BootstrapServices services;
    private int customerId;
    private int accountId;

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());
        services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts, infra.uowFactory);

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            UnitOfWork uow = scope.uow();
            customerId = infra.customers.nextId();
            Customer c = new Customer(customerId, "Split Probe", "split@example.com",
                    new Address("Hlavni 1", "Ostrava"));
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            // A ceiling and a soft tier high enough that neither fires: what this test is about
            // is the alert, and a refusal or a hold from another rule would prove nothing.
            infra.accounts.save(new Account(accountId, PAYER,
                    Money.czk(1_000_000), Money.czk(900_000), Money.czk(800_000)));
            c.addAccountId(accountId);
            infra.customers.save(c);
            uow.commit();
        }
    }

    @Test
    void theSecondHalfIsHeldWhenTheFirstHasAlreadySettled() {
        int first = pay(PAYEE);
        assertEquals(TransferStatus.WAITING_AUTH, statusOf(first),
                "each half is under the alert threshold on its own");
        services.transferService.authorizePayment(customerId, first, OTP);
        assertEquals(TransferStatus.SENT, statusOf(first));

        int second = pay(PAYEE);

        assertEquals(TransferStatus.HELD_FOR_REVIEW, statusOf(second),
                "6 500 already gone to this payee plus 6 500 more is over the threshold");
        assertTrue(alertOn(second).isPresent(), "and the analyst has something to look at");
    }

    /**
     * The ordering the rule cannot catch at creation, and the one the fix exists for.
     *
     * Both halves are created before either is authorized, so when the second is made the first
     * has not settled and is in no total. The rule can only see it at the moment the second is
     * confirmed - which is why the alert is asked again there, the way the daily ceiling has
     * been since A9.
     */
    @Test
    void theSecondHalfIsHeldAtAuthorizationWhenBothWereCreatedFirst() {
        int first = pay(PAYEE);
        int second = pay(PAYEE);

        assertEquals(TransferStatus.WAITING_AUTH, statusOf(first));
        assertEquals(TransferStatus.WAITING_AUTH, statusOf(second),
                "at creation the first half has not settled, so it is in no total");
        assertTrue(alertOn(second).isEmpty(), "and nothing could have been raised yet");

        services.transferService.authorizePayment(customerId, first, OTP);
        assertEquals(TransferStatus.SENT, statusOf(first));

        assertThrows(TransferUnderReviewException.class,
                () -> services.transferService.authorizePayment(customerId, second, OTP),
                "the second half must be refused at the moment it would settle");

        assertEquals(TransferStatus.HELD_FOR_REVIEW, statusOf(second));
        assertTrue(alertOn(second).isPresent());
    }

    @Test
    void aHoldAtAuthorizationSpendsNoOtpAttempt() {
        int first = pay(PAYEE);
        int second = pay(PAYEE);
        services.transferService.authorizePayment(customerId, first, OTP);

        assertThrows(TransferUnderReviewException.class,
                () -> services.transferService.authorizePayment(customerId, second, "9999"));

        assertEquals(0, transferOf(second).authAttempts(),
                "a refusal that is not about the code must cost nothing, and the code was not"
                        + " even looked at - this one was wrong");
    }

    /**
     * The loop this would otherwise create, closed.
     *
     * Without the once-per-transfer guard the analyst's approval would release the payment to
     * WAITING_AUTH, the next confirmation would hold it again on the same arithmetic, and the
     * customer could never complete it.
     */
    @Test
    void anApprovedHoldIsNotRaisedASecondTime() {
        int first = pay(PAYEE);
        int second = pay(PAYEE);
        services.transferService.authorizePayment(customerId, first, OTP);
        assertThrows(TransferUnderReviewException.class,
                () -> services.transferService.authorizePayment(customerId, second, OTP));

        services.fraudService.approve(second);
        assertEquals(TransferStatus.WAITING_AUTH, statusOf(second));

        services.transferService.authorizePayment(customerId, second, OTP);

        assertEquals(TransferStatus.SENT, statusOf(second),
                "an approved payment must be confirmable, or approval means nothing");
    }

    @Test
    void aPaymentToADifferentPayeeIsUntouched() {
        int first = pay(PAYEE);
        services.transferService.authorizePayment(customerId, first, OTP);

        int elsewhere = pay(OTHER_PAYEE);

        assertEquals(TransferStatus.WAITING_AUTH, statusOf(elsewhere),
                "the total is keyed on the payee; another payee starts at zero");
        assertTrue(alertOn(elsewhere).isEmpty());
    }

    /**
     * A stored snapshot that never went through IBAN's constructor still counts.
     *
     * Reachable through the public Transfer constructor, and CreditLegTest pins that such a row
     * must still resolve. A repository comparing snapshots as raw text would drop it from the
     * total and the split would go through unnoticed.
     */
    @Test
    void aDenormalizedStoredSnapshotIsStillTheSamePayee() {
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            Transfer settled = new Transfer(infra.transfers.nextId(), accountId, null,
                    "cz20 0100 0000 0000 1234 5678", Money.czk(HALF));
            settled.send(infra.accounts.byId(accountId).orElseThrow(), null,
                    services.feePolicy, settled.createdAt());
            infra.transfers.add(settled);
            infra.accounts.save(infra.accounts.byId(accountId).orElseThrow());
            scope.uow().commit();
        }

        int second = pay(PAYEE);

        assertEquals(TransferStatus.HELD_FOR_REVIEW, statusOf(second),
                "the spaced, lower-case snapshot names the same payee and must be counted");
    }

    // ------------------------------------------------------------------

    private int pay(String iban) {
        return services.transferService.submitPaymentToIban(
                customerId, accountId, iban, HALF, null);
    }

    private Transfer transferOf(int id) {
        return infra.transfers.byId(id).orElseThrow();
    }

    private TransferStatus statusOf(int id) {
        return transferOf(id).status();
    }

    private Optional<FraudAlert> alertOn(int transferId) {
        return infra.alerts.byTransferId(transferId);
    }
}
