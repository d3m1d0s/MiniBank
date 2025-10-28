// =========================
// Iteration 5 — Demo & Postconditions checker
// =========================
// Package: cz.vsb.minibank.demo
// Purpose: Spustí automatický scénář přes UC 04 → 05 → 11 (+ storno UC 19)
// a vypíše ověřitelné post‑conditions. Používá náš domain + application layer.
// Soubor dat: data/demo.json (aby neovlivňoval ruční testy v data/data.json)

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

public class DemoRunner {
    public static void main(String[] args) {
        String dataPath = "data/demo.json"; // oddělený soubor
        Bootstrap infra = new Bootstrap(dataPath);
        BootstrapServices services = new BootstrapServices(infra.customers, infra.accounts, infra.transfers, infra.alerts);

        int customerId = ensureDemoData(infra);
        int accountId = infra.accounts.byCustomerId(customerId).get(0).id();

        Money start = infra.accounts.byId(accountId).get().balance();
        System.out.println("[Start] Account=" + accountId + ", balance=" + start);

        try {
            // ===============
            // 1) UC 04 → 05 (AUTH REQUIRED)
            // Zadáme platbu na IBAN > dailyLimit (6000 CZK) → WAITING_AUTH, pak ji autorizujeme → SENT.
            // ===============
            int t1 = services.transferService.submitPaymentToIban(customerId, accountId,
                    "CZ0201000000000098765432", 6000, "demo AUTH");
            var tr1 = infra.transfers.byId(t1).orElseThrow();
            assertState(tr1.status() == TransferStatus.WAITING_AUTH, "T1 musí být WAITING_AUTH po vytvoření");
            services.transferService.authorizePayment(t1, "0000");
            tr1 = infra.transfers.byId(t1).orElseThrow();
            assertState(tr1.status() == TransferStatus.SENT, "T1 musí být SENT po autorizaci");

            var acc = infra.accounts.byId(accountId).orElseThrow();
            Money afterT1 = acc.balance();
            Money expectedFee1 = new SimpleFeePolicy().compute(Money.czk(6000)); // 30 CZK
            Money expectedBal1 = start.minus(Money.czk(6000).plus(expectedFee1));
            assertState(eq(afterT1, expectedBal1), "Zůstatek po T1 musí být snížen o částku+poplatek");
            System.out.println("[OK] UC04→UC05: T1 SENT, balance=" + afterT1 + " (fee=" + expectedFee1 + ") \n");

                    // ===============
                    // 2) UC 04 → 11 → 05 (FRAUD ALERT + APPROVE + AUTH)
                    // Nový nedůvěryhodný příjemce + částka 12000 CZK → FraudAlert{NEW} + WAITING_AUTH.
                    // Schválíme alert (approve) → zůstává WAITING_AUTH → autorizujeme OTP → SENT.
                    // ===============
            int benId = ensureUntrustedBeneficiary(infra, customerId, "Charlie Receiver", "CZ6508000000192000141111");
            int t2 = services.transferService.submitPaymentByBeneficiary(customerId, accountId, benId, 12000, "demo FRAUD");
            var tr2 = infra.transfers.byId(t2).orElseThrow();
            assertState(tr2.status() == TransferStatus.WAITING_AUTH, "T2 musí být WAITING_AUTH po vytvoření");
            var alert2 = infra.alerts.byTransferId(t2).orElseThrow();
            assertState(alert2.state() == FraudAlertState.NEW, "Alert pro T2 musí být NEW");

            services.fraudService.approve(t2); // alert → OK, transfer v WAITING_AUTH (vyžádá si klientskou autorizaci)
            alert2 = infra.alerts.byTransferId(t2).orElseThrow();
            assertState(alert2.state() == FraudAlertState.OK, "Alert T2 musí být OK po approve");
            tr2 = infra.transfers.byId(t2).orElseThrow();
            assertState(tr2.status() == TransferStatus.WAITING_AUTH, "T2 má stále WAITING_AUTH (nutná klientská autorizace)");

            services.transferService.authorizePayment(t2, "123456");
            tr2 = infra.transfers.byId(t2).orElseThrow();
            assertState(tr2.status() == TransferStatus.SENT, "T2 musí být SENT po autorizaci");

            acc = infra.accounts.byId(accountId).orElseThrow();
            Money afterT2 = acc.balance();
            Money expectedFee2 = new SimpleFeePolicy().compute(Money.czk(12000)); // 60 CZK
            Money expectedBal2 = start
                    .minus(Money.czk(6000).plus(expectedFee1))
                    .minus(Money.czk(12000).plus(expectedFee2));
            assertState(eq(afterT2, expectedBal2), "Zůstatek po T2 musí odpovídat 2. odchozí platbě");
            System.out.println("[OK] UC04→UC11→UC05: T2 SENT, balance=" + afterT2 + " (fee=" + expectedFee2 + ") \n");

                    // ===============
                    // 3) UC 19 (Cancel) — vytvoříme čekající platbu a zrušíme ji, zůstatek se nezmění.
                    // ===============
            int t3 = services.transferService.submitPaymentToIban(customerId, accountId,
                    "CZ6508000000192000142222", 5200, "demo CANCEL"); // WAITING_AUTH
            var tr3 = infra.transfers.byId(t3).orElseThrow();
            assertState(tr3.status() == TransferStatus.WAITING_AUTH, "T3 musí být WAITING_AUTH po vytvoření");
            services.transferService.cancelPayment(t3);
            tr3 = infra.transfers.byId(t3).orElseThrow();
            assertState(tr3.status() == TransferStatus.DECLINED, "T3 musí být DECLINED po stornu");

            acc = infra.accounts.byId(accountId).orElseThrow();
            Money afterT3 = acc.balance();
            assertState(eq(afterT3, expectedBal2), "Zůstatek se po stornu T3 nesmí změnit");
            System.out.println("[OK] UC19: T3 DECLINED, balance=" + afterT3 + " (beze změny)\n");

            // Shrnutí
            System.out.println("=== SUMMARY ===");
            System.out.println("Transfers: T1=" + t1 + " (SENT), T2=" + t2 + " (SENT), T3=" + t3 + " (DECLINED)");
            System.out.println("Post‑conditions ověřeny. Změny jsou perzistovány v " + dataPath);
        } catch (AssertionError ae) {
            System.err.println("[FAIL] " + ae.getMessage());
            System.exit(2);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(3);
        }
    }

    // === Helpers ===
    private static int ensureDemoData(Bootstrap infra) {
        var customers = infra.customers;
        var accounts = infra.accounts;
        int cid = 1;
        var exists = customers.byId(cid);
        if (exists.isPresent()) return cid;

        cid = customers.nextId();
        Customer c = new Customer(cid, "Demo User", "demo@example.com", new Address("Hlavní 9", "Ostrava"));
        customers.save(c);
        int accId = accounts.nextId();
        Account a = new Account(accId, new IBAN("CZ6508000000192000145399"), Money.czk(20000), Money.czk(5000));
        accounts.save(a);
        c.addAccountId(accId);
        customers.save(c);
        return cid;
    }

    private static int ensureUntrustedBeneficiary(Bootstrap infra, int customerId, String name, String iban) {
        int bid = infra.customers.nextBeneficiaryId();
        Beneficiary b = new Beneficiary(bid, name, new IBAN(iban), false);
        infra.customers.saveBeneficiary(customerId, b);
        return bid;
    }

    private static void assertState(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static boolean eq(Money a, Money b) {
        return a.amount().setScale(2).equals(b.amount().setScale(2)) && a.currency().equals(b.currency());
    }
}

/*
RUN:
  1) Přidej závislost Jackson (pokud ještě není) a zbuilduj projekt.
  2) Spusť `cz.vsb.minibank.demo.DemoRunner`.
EXPECT:
  - T1: WAITING_AUTH → (Authorize 0000) → SENT, balance snížen o 6000 + 30.
  - T2: vytvořen FraudAlert{NEW}, approve → alert OK, transfer WAITING_AUTH → Authorize 123456 → SENT.
  - T3: WAITING_AUTH → Cancel → DECLINED, balance beze změny.
  - Soubor `data/demo.json` bude obsahovat nové zákazníka/účet/příjemce/transfery/alerty se stavy dle výše.
*/
