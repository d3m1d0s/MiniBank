package cz.vsb.minibank.demo;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.FixedOtpValidator;
import cz.vsb.minibank.application.OtpValidator;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.application.FraudApplicationService;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.domain.value.*;
import cz.vsb.minibank.infrastructure.Bootstrap;

import java.math.BigDecimal;

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
 * Demo data are stored in data/demo.json so they do not affect manual tests
 * that use data/data.json.
 */
public class DemoRunner {
    public static void main(String[] args) {
        String dataPath = "data/demo.json"; // separate file used only for the demo scenario
        Bootstrap infra = new Bootstrap(dataPath);
        BootstrapServices services = new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory
        );

        int customerId = ensureDemoData(infra);
        int accountId = infra.accounts.byCustomerId(customerId).get(0).id();

        Money start = infra.accounts.byId(accountId).get().balance();
        System.out.println("[Start] Account=" + accountId + ", balance=" + start);

        try {
            // 1) UC 04 -> 05 (AUTH REQUIRED)
            // Submit a payment to an IBAN above dailyLimit (6000 CZK) -> WAITING_AUTH,
            // then authorize -> SENT.
            int t1 = services.transferService.submitPaymentToIban(
                    customerId,
                    accountId,
                    "CZ0201000000000098765432",
                    6000,
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
            Money expectedFee1 = new SimpleFeePolicy().compute(Money.czk(6000)); // 30 CZK
            Money expectedBal1 = start.minus(Money.czk(6000).plus(expectedFee1));
            assertState(eq(afterT1, expectedBal1),
                    "Balance after T1 must be reduced by amount and fee");
            System.out.println("[OK] UC04->UC05: T1 SENT, balance=" + afterT1
                    + " (fee=" + expectedFee1 + ") \n");

            // 2) UC 04 -> 11 -> 05 (FRAUD ALERT + APPROVE + AUTH)
            // New untrusted beneficiary and amount 12,000 CZK -> FraudAlert{NEW} + WAITING_AUTH.
            // Approve the alert -> still WAITING_AUTH -> authorize via OTP -> SENT.
            int benId = ensureUntrustedBeneficiary(
                    infra,
                    customerId,
                    "Charlie Receiver",
                    "CZ6508000000192000141111"
            );
            int t2 = services.transferService.submitPaymentByBeneficiary(
                    customerId,
                    accountId,
                    benId,
                    12000,
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
            Money expectedFee2 = new SimpleFeePolicy().compute(Money.czk(12000)); // 60 CZK
            Money expectedBal2 = start
                    .minus(Money.czk(6000).plus(expectedFee1))
                    .minus(Money.czk(12000).plus(expectedFee2));
            assertState(eq(afterT2, expectedBal2),
                    "Balance after T2 must reflect the second outgoing payment");
            System.out.println("[OK] UC04->UC11->UC05: T2 SENT, balance=" + afterT2
                    + " (fee=" + expectedFee2 + ") \n");

            // 3) UC 19 (Cancel) - create a pending payment and cancel it;
            // the balance stays unchanged.
            int t3 = services.transferService.submitPaymentToIban(
                    customerId,
                    accountId,
                    "CZ6508000000192000142222",
                    5200,
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
            assertState(eq(afterT3, expectedBal2),
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

    private static int ensureDemoData(Bootstrap infra) {
        var customers = infra.customers;
        var accounts = infra.accounts;
        int cid = 1;
        var exists = customers.byId(cid);
        if (exists.isPresent()) return cid;

        cid = customers.nextId();
        Customer c = new Customer(
                cid,
                "Demo User",
                "demo@example.com",
                new Address("Hlavni 9", "Ostrava")
        );
        customers.save(c);
        int accId = accounts.nextId();
        Account a = new Account(
                accId,
                new IBAN("CZ6508000000192000145399"),
                Money.czk(20000),
                Money.czk(5000)
        );
        accounts.save(a);
        c.addAccountId(accId);
        customers.save(c);
        return cid;
    }

    private static int ensureUntrustedBeneficiary(
            Bootstrap infra,
            int customerId,
            String name,
            String iban
    ) {
        int bid = infra.customers.nextBeneficiaryId();
        Beneficiary b = new Beneficiary(bid, name, new IBAN(iban), false);
        infra.customers.saveBeneficiary(customerId, b);
        return bid;
    }

    private static void assertState(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static boolean eq(Money a, Money b) {
        return a.amount().setScale(2).equals(b.amount().setScale(2))
                && a.currency().equals(b.currency());
    }
}
