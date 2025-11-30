package cz.vsb.minibank.ui.console;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.application.FraudApplicationService;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import cz.vsb.minibank.infrastructure.Bootstrap;

import java.util.*;

public class ConsoleMenu {
    private final BootstrapServices services;
    private final Bootstrap infra;
    private final int customerId;
    private final Scanner in = new Scanner(System.in);
    private final List<ConsoleCommand> commands = new ArrayList<>();

    public ConsoleMenu(BootstrapServices services, Bootstrap infra, int customerId) {
        this.services = services;
        this.infra = infra;
        this.customerId = customerId;

        // NOTE: SimpleCommand is an internal implementation of ConsoleCommand
        commands.add(new SimpleCommand("1", "Display customer and accounts",
                this::showCustomerAndAccounts));
        commands.add(new SimpleCommand("2", "Add beneficiary",
                this::addBeneficiary));
        commands.add(new SimpleCommand("3", "Send payment to a saved beneficiary",
                this::submitPaymentByBeneficiary));
        commands.add(new SimpleCommand("4", "Send payment to IBAN",
                this::submitPaymentToIban));
        commands.add(new SimpleCommand("5", "Authorize payment (UC 05)",
                this::authorizePayment));
        commands.add(new SimpleCommand("6", "Fraud alerts: list / approve / decline / request (UC 11/12)",
                this::fraudMenu));
        commands.add(new SimpleCommand("7", "Cancel payment (UC 19)",
                this::cancelPayment));
        commands.add(new SimpleCommand("8", "List transfers by account",
                this::listTransfersByAccount));
        // The "Exit" command could be a separate command, but it is simpler to handle it in run()
    }

    /**
     * Simple implementation of ConsoleCommand that delegates to a Runnable.
     * This is our concrete Command type.
     */
    private static final class SimpleCommand implements ConsoleCommand {
        private final String code;
        private final String description;
        private final Runnable action;

        SimpleCommand(String code, String description, Runnable action) {
            this.code = code;
            this.description = description;
            this.action = action;
        }

        @Override public String code() { return code; }
        @Override public String description() { return description; }

        @Override
        public void execute() {
            // You can add user action logging here:
            // System.out.println("[CMD] " + code + " - " + description);
            action.run();
        }
    }

    public void run() {
        while (true) {
            System.out.println("\n=== Mini-bank (Domain Model) ===");
            for (ConsoleCommand cmd : commands) {
                System.out.printf("%s) %s%n", cmd.code(), cmd.description());
            }
            System.out.println("9) Exit");
            System.out.print("Choice: ");
            String choice = in.nextLine().trim();

            if ("9".equals(choice)) {
                System.out.println("Bye!");
                return;
            }

            ConsoleCommand cmd = commands.stream()
                    .filter(c -> c.code().equals(choice))
                    .findFirst()
                    .orElse(null);

            if (cmd == null) {
                System.out.println("Invalid choice");
                continue;
            }

            try {
                cmd.execute();
            } catch (Exception e) {
                System.out.println("[Error] " + e.getMessage());
            }
        }
    }

    private void showCustomerAndAccounts() {
        var cust = infra.customers.byId(customerId).orElseThrow();
        System.out.println("\nCustomer: " + cust.name() + " (id=" + cust.id() + ")");
        System.out.println("Email: " + cust.email());
        System.out.println("Address: " + cust.address().street() + ", " + cust.address().city());
        System.out.println("Accounts:");
        var accounts = infra.accounts.byCustomerId(customerId);
        for (Account a : accounts) {
            System.out.println("  - id=" + a.id() + ", IBAN=" + a.iban().value() + ", balance=" + a.balance());
        }
        System.out.println("Beneficiaries:");
        for (Beneficiary b : cust.beneficiaries()) {
            System.out.println("  - id=" + b.id() + ", " + b.name() + ", IBAN=" + b.iban().value() + ", trusted=" + b.trusted());
        }
    }

    private void addBeneficiary() {
        UnitOfWork uow = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            var cust = infra.customers.byId(customerId).orElseThrow();

            System.out.print("Beneficiary name: ");
            String name = in.nextLine().trim();

            System.out.print("IBAN (e.g., CZ0201000000000012345678): ");
            String iban = in.nextLine().trim();

            System.out.print("Trusted? (y/N): ");
            boolean trusted = in.nextLine().trim().equalsIgnoreCase("y");

            int bid = infra.customers.nextBeneficiaryId();
            Beneficiary b = new Beneficiary(bid, name, new IBAN(iban), trusted);

            infra.customers.saveBeneficiary(customerId, b);

            uow.commit();
            System.out.println("[OK] Beneficiary added id=" + bid);
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    private void submitPaymentByBeneficiary() {
        var accounts = infra.accounts.byCustomerId(customerId);
        if (accounts.isEmpty()) { System.out.println("No accounts"); return; }
        int accId = askInt("Source account id", accounts.get(0).id());
        int benId = askInt("Beneficiary id", -1);
        double amount = askDouble("Amount CZK", 1000);
        int tid = services.transferService.submitPaymentByBeneficiary(customerId, accId, benId, amount, "");
        System.out.println("[OK] Transfer created id=" + tid);
    }

    private void submitPaymentToIban() {
        var accounts = infra.accounts.byCustomerId(customerId);
        if (accounts.isEmpty()) { System.out.println("No accounts"); return; }
        int accId = askInt("Source account id", accounts.get(0).id());
        System.out.print("Target IBAN: ");
        String iban = in.nextLine().trim();
        double amount = askDouble("Amount CZK", 6000);
        int tid = services.transferService.submitPaymentToIban(customerId, accId, iban, amount, "");
        System.out.println("[OK] Transfer created id=" + tid);
    }

    private void authorizePayment() {
        int tid = askInt("Transfer id", -1);
        System.out.print("OTP (0000/123456): ");
        String otp = in.nextLine().trim();
        services.transferService.authorizePayment(tid, otp);
        var t = infra.transfers.byId(tid).orElseThrow();
        System.out.println("[Result] Transfer " + tid + " has status " + t.status());
        var acc = infra.accounts.byId(t.sourceAccountId()).orElseThrow();
        System.out.println("Account id=" + acc.id() + " balance=" + acc.balance());
    }

    private void fraudMenu() {
        var list = infra.alerts.all();
        if (list.isEmpty()) { System.out.println("No alerts"); return; }
        System.out.println("Alerts:");
        for (FraudAlert a : list) {
            System.out.println("  - id=" + a.id() + ", transfer=" + a.transferId() + ", state=" + a.state() + ", reason=" + a.reason());
        }
        System.out.print("Action (approve/decline/request): ");
        String act = in.nextLine().trim();
        int tid = askInt("Transfer id", -1);
        switch (act.toLowerCase()) {
            case "approve" -> services.fraudService.approve(tid);
            case "decline" -> {
                System.out.print("Reason: ");
                String reason = in.nextLine().trim();
                services.fraudService.decline(tid, reason.isEmpty() ? "Declined" : reason);
            }
            case "request" -> services.fraudService.requestCustomerConfirmation(tid);
            default -> System.out.println("Unknown action");
        }
    }

    private void cancelPayment() {
        int tid = askInt("Transfer id", -1);
        services.transferService.cancelPayment(tid);
        var t = infra.transfers.byId(tid).orElseThrow();
        System.out.println("[Result] Transfer " + tid + " has status " + t.status());
    }

    private void listTransfersByAccount() {
        var accounts = infra.accounts.byCustomerId(customerId);
        if (accounts.isEmpty()) { System.out.println("No accounts"); return; }
        int accId = askInt("Account id", accounts.get(0).id());
        var list = infra.transfers.bySourceAccount(accId);
        if (list.isEmpty()) { System.out.println("No transfers"); return; }
        for (Transfer t : list) {
            System.out.println("  - id=" + t.id() + ", status=" + t.status() + ", amount=" + t.amount() + ", to=" + t.targetIbanSnapshot());
        }
    }

    private int askInt(String label, int defVal) {
        System.out.print(label + (defVal >= 0 ? " [" + defVal + "]" : "") + ": ");
        String s = in.nextLine().trim();
        if (s.isEmpty() && defVal >= 0) return defVal;
        return Integer.parseInt(s);
    }

    private double askDouble(String label, double defVal) {
        System.out.print(label + " [" + defVal + "]: ");
        String s = in.nextLine().trim();
        if (s.isEmpty()) return defVal;
        return Double.parseDouble(s);
    }
}
