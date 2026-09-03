package cz.vsb.minibank.demo;

import cz.vsb.minibank.application.config.BootstrapServices;
import cz.vsb.minibank.application.audit.FraudAlertAuditLogObserver;
import cz.vsb.minibank.application.config.MinibankProperties;
import cz.vsb.minibank.application.payment.PaymentDispatcher;
import cz.vsb.minibank.application.audit.TransferAuditLogObserver;
import cz.vsb.minibank.domain.customer.*;
import cz.vsb.minibank.domain.fee.*;
import cz.vsb.minibank.domain.fraud.*;
import cz.vsb.minibank.domain.transfer.*;
import cz.vsb.minibank.domain.exceptions.TransferUnderReviewException;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Demo runner that executes an automated end-to-end scenario over the domain and
 * application layer and prints verifiable post-conditions.
 * <p>
 * It covers:
 * <ul>
 *   <li>UC 04 -> UC 05: payment that requires authorization</li>
 *   <li>UC 04 -> UC 11 -> UC 05: fraud alert, approval and authorization</li>
 *   <li>UC 19: cancel payment</li>
 * </ul>
 * The starting dataset comes from {@link DemoScenario}, and the store is kept
 * separate from the one the console app uses so the two do not interfere.
 */
public class DemoRunner {

    /** Above the 5 000 CZK authorization threshold. */
    private static final double AUTH_AMOUNT = 6_000;
    /** Above the 10 000 CZK fraud-alert threshold. */
    private static final double FRAUD_AMOUNT = 12_000;
    /** Above the authorization threshold as well, but cancelled instead of sent. */
    private static final double CANCEL_AMOUNT = 5_200;

    /**
     * Below every threshold that judges a payment on its own: untrusted, but under the 5 000
     * untrusted threshold and nowhere near the 10 000 alert threshold, and comfortably inside the
     * secondary account's 5 000 opening balance. Nothing about the payment itself can hold it.
     *
     * What holds it is the day it lands in. The two payments above have already taken 18 000 out
     * of the primary account, and the day's running total the soft tier is measured against is
     * the customer's, not one account's. This is the only step in the script that would settle on
     * the spot if that total went back to being counted per account.
     */
    private static final double SOFT_TIER_AMOUNT = 3_500;

    public static void main(String[] args) {
        // Its own key rather than minibank.json.path, so pointing the demo somewhere else
        // cannot silently repoint the console app and the API at the demo store as well.
        String dataPath = MinibankProperties.demoPath();
        if (MinibankProperties.demoReset()) {
            resetStore(dataPath);
        }
        Bootstrap infra = new Bootstrap(dataPath);

        // The bus belongs to this Bootstrap and lives exactly as long as it does. Registering
        // here, at the one place that starts the process, and not in BootstrapServices, which
        // anything may construct any number of times.
        infra.events.register(new TransferAuditLogObserver());
        infra.events.register(new FraudAlertAuditLogObserver());

        BootstrapServices services = new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory
        );

        // The third observer, and the only one that does something rather than record something.
        // Attached here for the reason the audit pair is, and from the gateway BootstrapServices
        // built, so this process has exactly one thing talking to the network.
        PaymentDispatcher dispatcher = new PaymentDispatcher(
                services.paymentGateway, infra.transfers, infra.uowFactory);
        infra.events.register(dispatcher);

        int customerId = new DemoScenario(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory,
                services.feePolicy
        ).seed();

        // After the seed, which commits a settled payment out of this bank and therefore writes a
        // dispatch this run owes, and before the script, so the two do not have to be told apart
        // in the output.
        dispatcher.sweepPending();

        Account account = infra.accounts.byIban(DemoScenario.PRIMARY_IBAN).orElseThrow();
        int accountId = account.id();
        Money start = account.balance();
        System.out.println("[Start] Account=" + accountId + ", balance=" + start);

        try {
            requireSufficientFunds(start, services.feePolicy);

            // 1) UC 04 -> 05 (AUTH REQUIRED)
            // Submit a payment above the authorization threshold -> WAITING_AUTH,
            // then authorize -> SENT.
            int t1 = services.transferService.submitPaymentToIban(
                    customerId,
                    accountId,
                    "CZ1301000000000098765432",
                    AUTH_AMOUNT,
                    "demo AUTH"
            ).transferId();
            var tr1 = infra.transfers.byId(t1).orElseThrow();
            assertState(tr1.status() == TransferStatus.WAITING_AUTH,
                    "T1 must be WAITING_AUTH after creation");
            services.transferService.authorizePayment(customerId, t1, "0000");
            tr1 = infra.transfers.byId(t1).orElseThrow();
            assertState(tr1.status() == TransferStatus.SENT,
                    "T1 must be SENT after authorization");

            var acc = infra.accounts.byId(accountId).orElseThrow();
            Money afterT1 = acc.balance();
            Money expectedFee1 = services.feePolicy.compute(Money.czk(AUTH_AMOUNT));
            Money expectedBal1 = start.minus(Money.czk(AUTH_AMOUNT).plus(expectedFee1));
            assertState(afterT1.equals(expectedBal1),
                    "Balance after T1 must be reduced by amount and fee");
            System.out.println("[OK] UC04->UC05: T1 SENT, balance=" + afterT1
                    + " (fee=" + expectedFee1 + ") \n");

            // 2) UC 04 -> 11 -> 05 (FRAUD ALERT + APPROVE + AUTH)
            // Untrusted beneficiary above the alert threshold -> FraudAlert{NEW} +
            // HELD_FOR_REVIEW. A valid code is refused while the alert is open. Approve the
            // alert -> released to WAITING_AUTH, and no money has moved -> authorize via OTP
            // -> SENT.
            int benId = untrustedBeneficiaryOf(infra, customerId);
            int t2 = services.transferService.submitPaymentByBeneficiary(
                    customerId,
                    accountId,
                    benId,
                    FRAUD_AMOUNT,
                    "demo FRAUD"
            ).transferId();
            var tr2 = infra.transfers.byId(t2).orElseThrow();
            assertState(tr2.status() == TransferStatus.HELD_FOR_REVIEW,
                    "T2 must be HELD_FOR_REVIEW after creation");
            var alert2 = infra.alerts.byTransferId(t2).orElseThrow();
            assertState(alert2.state() == FraudAlertState.NEW,
                    "Alert for T2 must be NEW");

            // The gate, exercised end to end: "123456" is a code FixedOtpValidator accepts, and
            // it is refused anyway because the alert is open. This is the one place the whole
            // feature is visible in a single script.
            Money beforeGate = infra.accounts.byId(accountId).orElseThrow().balance();
            try {
                services.transferService.authorizePayment(customerId, t2, "123456");
                throw new AssertionError("T2 must not be authorizable while its alert is open");
            } catch (TransferUnderReviewException expected) {
                System.out.println("[OK] A held transfer refuses a valid code");
            }
            tr2 = infra.transfers.byId(t2).orElseThrow();
            assertState(tr2.status() == TransferStatus.HELD_FOR_REVIEW,
                    "A refused authorization must leave T2 held");
            assertState(tr2.authAttempts() == 0,
                    "A refusal that is not about the code must spend no OTP attempt");
            assertState(infra.accounts.byId(accountId).orElseThrow().balance().equals(beforeGate),
                    "A refused authorization must move nothing");

            services.fraudService.approve(t2);
            alert2 = infra.alerts.byTransferId(t2).orElseThrow();
            assertState(alert2.state() == FraudAlertState.OK,
                    "Alert for T2 must be OK after approve");
            tr2 = infra.transfers.byId(t2).orElseThrow();
            assertState(tr2.status() == TransferStatus.WAITING_AUTH,
                    "T2 is released to WAITING_AUTH; approve does not send the money");
            assertState(infra.accounts.byId(accountId).orElseThrow().balance().equals(beforeGate),
                    "Approving an alert must not debit anything");

            services.transferService.authorizePayment(customerId, t2, "123456");
            tr2 = infra.transfers.byId(t2).orElseThrow();
            assertState(tr2.status() == TransferStatus.SENT,
                    "T2 must be SENT after authorization");

            acc = infra.accounts.byId(accountId).orElseThrow();
            Money afterT2 = acc.balance();
            Money expectedFee2 = services.feePolicy.compute(Money.czk(FRAUD_AMOUNT));
            Money expectedBal2 = expectedBal1.minus(Money.czk(FRAUD_AMOUNT).plus(expectedFee2));
            assertState(afterT2.equals(expectedBal2),
                    "Balance after T2 must reflect the second outgoing payment");
            System.out.println("[OK] UC04->UC11->UC05: T2 SENT, balance=" + afterT2
                    + " (fee=" + expectedFee2 + ") \n");

            // 3) UC 19 (Cancel) - create a pending payment and cancel it;
            // the balance stays unchanged.
            int t3 = services.transferService.submitPaymentToIban(
                    customerId,
                    accountId,
                    "CZ9608000000192000142222",
                    CANCEL_AMOUNT,
                    "demo CANCEL"
            ).transferId();
            var tr3 = infra.transfers.byId(t3).orElseThrow();
            assertState(tr3.status() == TransferStatus.WAITING_AUTH,
                    "T3 must be WAITING_AUTH after creation");
            services.transferService.cancelPayment(customerId, t3);
            tr3 = infra.transfers.byId(t3).orElseThrow();
            assertState(tr3.status() == TransferStatus.DECLINED,
                    "T3 must be DECLINED after cancel");

            acc = infra.accounts.byId(accountId).orElseThrow();
            Money afterT3 = acc.balance();
            assertState(afterT3.equals(expectedBal2),
                    "Balance must remain unchanged after canceling T3");
            System.out.println("[OK] UC19: T3 DECLINED, balance=" + afterT3 + " (unchanged)\n");

            // 4) The soft authorization tier, counted across the customer rather than per account.
            // Everything above runs on the primary account; this pays from the secondary one and
            // is held anyway. Without this step nothing in the script would notice if the day's
            // total went back to being one allowance per account.
            runSoftTierStep(infra, services, customerId);

            System.out.println("=== SUMMARY ===");
            System.out.println("Transfers: T1=" + t1 + " (SENT), T2=" + t2
                    + " (SENT), T3=" + t3 + " (DECLINED)");
            System.out.println("Post-conditions verified. Changes are persisted in " + dataPath);
        } catch (AssertionError ae) {
            System.err.println("[FAIL] " + ae.getMessage());
            System.exit(2);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(3);
        }
    }

    // Helpers

    /**
     * Sends one payment from the secondary account and shows that the soft authorization tier is
     * measured over the customer's whole day rather than over one account's.
     *
     * The secondary account has sent nothing today and the amount is under every single-payment
     * threshold, so on its own the payment settles. It is held because the primary account has
     * already spent 18 000 of the same allowance. The threshold read here is the customer's, and
     * this customer has none of their own, so it is the bank-wide tier that applies.
     */
    private static void runSoftTierStep(Bootstrap infra, BootstrapServices services, int customerId) {
        Customer customer = infra.customers.byId(customerId).orElseThrow();
        Money threshold = customer.softDailyThreshold();
        String tier = (threshold != null)
                ? "this customer's own " + threshold + " soft tier"
                : "the bank-wide soft tier";

        Account secondary = infra.accounts.byIban(DemoScenario.SECONDARY_IBAN).orElseThrow();
        Money before = secondary.balance();
        int t4 = services.transferService.submitPaymentToIban(
                customerId,
                secondary.id(),
                "CZ1301000000000098765432",
                SOFT_TIER_AMOUNT,
                "demo SOFT TIER"
        ).transferId();
        var tr4 = infra.transfers.byId(t4).orElseThrow();
        assertState(tr4.status() == TransferStatus.WAITING_AUTH,
                "T4 of " + Money.czk(SOFT_TIER_AMOUNT) + " must be held by " + tier
                        + ", which the day's earlier payments out of the other account crossed;"
                        + " counted per account this one would have settled");
        assertState(infra.accounts.byId(secondary.id()).orElseThrow().balance().equals(before),
                "A payment held for authorization must not have debited anything");

        services.transferService.authorizePayment(customerId, t4, "0000");
        tr4 = infra.transfers.byId(t4).orElseThrow();
        assertState(tr4.status() == TransferStatus.SENT, "T4 must be SENT after authorization");

        Money fee4 = services.feePolicy.compute(Money.czk(SOFT_TIER_AMOUNT));
        Money after = infra.accounts.byId(secondary.id()).orElseThrow().balance();
        assertState(after.equals(before.minus(Money.czk(SOFT_TIER_AMOUNT).plus(fee4))),
                "Balance after T4 must be reduced by amount and fee");
        assertState(tr4.fee() != null && tr4.fee().equals(fee4),
                "the settled transfer must carry the fee it was charged, not recompute it");
        assertState(tr4.settledAt() != null,
                "The settled transfer must record when the money moved");

        System.out.println("[OK] Customer-wide soft tier: T4=" + t4 + " left the secondary account"
                + " and was held anyway by " + tier + ", already crossed by the primary account's"
                + " payments, then SENT, balance=" + after + " (fee=" + fee4
                + ", stored not recomputed)\n");
    }

    /**
     * Discards the demo store so the run starts from the dataset the scenario
     * builds. Only ever called when explicitly requested, because silently
     * wiping a store on every run would be worse than failing loudly.
     */
    private static void resetStore(String dataPath) {
        Path store = Paths.get(dataPath);
        try {
            if (Files.deleteIfExists(store)) {
                System.out.println("[Reset] Discarded " + store.toAbsolutePath());
            } else {
                System.out.println("[Reset] Nothing to discard at " + store.toAbsolutePath());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot discard the demo store at " + store.toAbsolutePath(), e);
        }
    }

    /**
     * The script sends two payments and needs a third one to be creatable, so it
     * cannot start from an arbitrary balance. Checking up front turns a confusing
     * failure in the middle of the run into one statement of what was needed.
     */
    private static void requireSufficientFunds(Money balance, FeePolicy feePolicy) {
        Money required = totalWithFee(Money.czk(AUTH_AMOUNT), feePolicy)
                .plus(totalWithFee(Money.czk(FRAUD_AMOUNT), feePolicy))
                .plus(totalWithFee(Money.czk(CANCEL_AMOUNT), feePolicy));

        assertState(balance.gte(required),
                "The demo needs at least " + required + " but the account holds " + balance
                        + ". Re-run with -D" + MinibankProperties.DEMO_RESET
                        + "=true to start from a fresh dataset.");
    }

    private static Money totalWithFee(Money amount, FeePolicy feePolicy) {
        return amount.plus(feePolicy.compute(amount));
    }

    /**
     * Reuses the untrusted beneficiary created by the scenario instead of adding
     * another one on every run.
     */
    private static int untrustedBeneficiaryOf(Bootstrap infra, int customerId) {
        Customer customer = infra.customers.byId(customerId).orElseThrow();
        return customer.beneficiaries().stream()
                .filter(b -> !b.trusted())
                .findFirst()
                .orElseThrow(() -> new AssertionError("The demo dataset has no untrusted beneficiary"))
                .id();
    }

    private static void assertState(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
