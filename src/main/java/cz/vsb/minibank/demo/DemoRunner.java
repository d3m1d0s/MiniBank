package cz.vsb.minibank.demo;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.domain.*;
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

    /** Kept apart from the console store so the two do not interfere; storage/ is gitignored. */
    static final String DEFAULT_DEMO_PATH = "storage/demo.json";

    /** Opt-in: discard the demo store before running, so the script starts from a known state. */
    static final String RESET_PROPERTY = "minibank.demo.reset";

    public static void main(String[] args) {
        String dataPath = System.getProperty("minibank.json.path", DEFAULT_DEMO_PATH);
        if (Boolean.getBoolean(RESET_PROPERTY)) {
            resetStore(dataPath);
        }
        Bootstrap infra = new Bootstrap(dataPath);
        BootstrapServices services = new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory
        );

        int customerId = new DemoScenario(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory,
                services.feePolicy
        ).seed();

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
                    "CZ0201000000000098765432",
                    AUTH_AMOUNT,
                    "demo AUTH"
            );
            var tr1 = infra.transfers.byId(t1).orElseThrow();
            assertState(tr1.status() == TransferStatus.WAITING_AUTH,
                    "T1 must be WAITING_AUTH after creation");
            services.transferService.authorizePayment(t1, "0000");
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
            // Untrusted beneficiary above the alert threshold -> FraudAlert{NEW} + WAITING_AUTH.
            // Approve the alert -> still WAITING_AUTH -> authorize via OTP -> SENT.
            int benId = untrustedBeneficiaryOf(infra, customerId);
            int t2 = services.transferService.submitPaymentByBeneficiary(
                    customerId,
                    accountId,
                    benId,
                    FRAUD_AMOUNT,
                    "demo FRAUD"
            );
            var tr2 = infra.transfers.byId(t2).orElseThrow();
            assertState(tr2.status() == TransferStatus.WAITING_AUTH,
                    "T2 must be WAITING_AUTH after creation");
            var alert2 = infra.alerts.byTransferId(t2).orElseThrow();
            assertState(alert2.state() == FraudAlertState.NEW,
                    "Alert for T2 must be NEW");

            services.fraudService.approve(t2);
            alert2 = infra.alerts.byTransferId(t2).orElseThrow();
            assertState(alert2.state() == FraudAlertState.OK,
                    "Alert for T2 must be OK after approve");
            tr2 = infra.transfers.byId(t2).orElseThrow();
            assertState(tr2.status() == TransferStatus.WAITING_AUTH,
                    "T2 still has WAITING_AUTH (customer authorization required)");

            services.transferService.authorizePayment(t2, "123456");
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
                    "CZ6508000000192000142222",
                    CANCEL_AMOUNT,
                    "demo CANCEL"
            );
            var tr3 = infra.transfers.byId(t3).orElseThrow();
            assertState(tr3.status() == TransferStatus.WAITING_AUTH,
                    "T3 must be WAITING_AUTH after creation");
            services.transferService.cancelPayment(t3);
            tr3 = infra.transfers.byId(t3).orElseThrow();
            assertState(tr3.status() == TransferStatus.DECLINED,
                    "T3 must be DECLINED after cancel");

            acc = infra.accounts.byId(accountId).orElseThrow();
            Money afterT3 = acc.balance();
            assertState(afterT3.equals(expectedBal2),
                    "Balance must remain unchanged after canceling T3");
            System.out.println("[OK] UC19: T3 DECLINED, balance=" + afterT3 + " (unchanged)\n");

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
                        + ". Re-run with -D" + RESET_PROPERTY + "=true to start from a fresh dataset.");
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
