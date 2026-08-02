package cz.vsb.minibank.ui.console;

import cz.vsb.minibank.application.AuthService;
import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.exceptions.AccessDeniedException;
import cz.vsb.minibank.domain.exceptions.AuthenticationFailedException;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.InvalidOtpException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import cz.vsb.minibank.application.AppLogger;
import cz.vsb.minibank.domain.exceptions.DomainException;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Text-based console UI for interacting with the minibank domain model.
 * Supports both legacy mode with a fixed customer and login-based mode with roles.
 */
public class ConsoleMenu {
    private final BootstrapServices services;
    private final Bootstrap infra;
    private final AuthService authService;        // null = legacy mode without login
    private final int customerId;                 // >0 = legacy mode with fixed customer

    private final Scanner in = new Scanner(System.in);
    private final List<ConsoleCommand> commands = new ArrayList<>();

    private User currentUser;                     // user obtained from AuthService/login

    // Legacy constructor: used by tests and App (JSON mode)
    public ConsoleMenu(BootstrapServices services, Bootstrap infra, int customerId) {
        this.services = services;
        this.infra = infra;
        this.authService = null;
        this.customerId = customerId;
        initCommands();
    }

    // New constructor: used by AppSql, with login and roles
    public ConsoleMenu(BootstrapServices services, Bootstrap infra, AuthService authService) {
        this.services = services;
        this.infra = infra;
        this.authService = authService;
        this.customerId = -1;
        initCommands();
    }

    /**
     * Simple implementation of ConsoleCommand that delegates to a Runnable.
     * Supports RBAC: allowedRoles == null means the command is visible to all roles.
     */
    private static final class SimpleCommand implements ConsoleCommand {
        private final String code;
        private final String description;
        private final Runnable action;
        private final UserRole[] allowedRoles; // null = visible to all roles

        SimpleCommand(String code, String description, Runnable action) {
            this(code, description, action, (UserRole[]) null);
        }

        SimpleCommand(String code,
                      String description,
                      Runnable action,
                      UserRole... allowedRoles) {
            this.code = code;
            this.description = description;
            this.action = action;
            this.allowedRoles = allowedRoles;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public void execute() {
            action.run();
        }

        @Override
        public boolean isVisibleFor(UserRole role) {
            if (allowedRoles == null || role == null) {
                return true;
            }
            for (UserRole r : allowedRoles) {
                if (r == role) return true;
            }
            return false;
        }
    }

    private void initCommands() {
        // CUSTOMER commands
        commands.add(new SimpleCommand(
                "1",
                "Display customer and accounts",
                this::showCustomerAndAccounts,
                UserRole.CUSTOMER
        ));
        commands.add(new SimpleCommand(
                "2",
                "Add beneficiary",
                this::addBeneficiary,
                UserRole.CUSTOMER
        ));
        commands.add(new SimpleCommand(
                "3",
                "Send payment to a saved beneficiary",
                this::submitPaymentByBeneficiary,
                UserRole.CUSTOMER
        ));
        commands.add(new SimpleCommand(
                "4",
                "Send payment to IBAN",
                this::submitPaymentToIban,
                UserRole.CUSTOMER
        ));
        commands.add(new SimpleCommand(
                "5",
                "Authorize payment (UC 05)",
                this::authorizePayment,
                UserRole.CUSTOMER
        ));
        commands.add(new SimpleCommand(
                "7",
                "Cancel payment (UC 19)",
                this::cancelPayment,
                UserRole.CUSTOMER
        ));
        commands.add(new SimpleCommand(
                "8",
                "List transfers by account",
                this::listTransfersByAccount,
                UserRole.CUSTOMER
        ));

        // FRAUD_ANALYST commands
        commands.add(new SimpleCommand(
                "6",
                "Fraud alerts: list / approve / decline / request (UC 11/12)",
                this::fraudMenu,
                UserRole.FRAUD_ANALYST
        ));

        // Exit is kept out of the list and handled as option 9
    }

    /**
     * Login is required only when:
     * - AuthService is available (new mode)
     * - and there is no fixed customerId (legacy mode).
     */
    private void loginIfNeeded() {
        if (authService == null || customerId > 0) {
            return;
        }

        while (true) {
            System.out.println("=== Login ===");
            System.out.print("Username: ");
            String username = in.nextLine().trim();

            System.out.print("Password: ");
            String password = in.nextLine();

            try {
                currentUser = authService.login(username, password.toCharArray());
                System.out.println("Welcome, " + currentUser.username()
                        + " (" + currentUser.role() + ")");
                break;
            } catch (AuthenticationFailedException e) {
                System.out.println("Invalid username or password, please try again.");
            }
        }
    }

    /**
     * Resolves the customer id for a CUSTOMER:
     * - in the new mode it is taken from currentUser.customerId()
     * - in the legacy mode it uses the customerId field.
     */
    /**
     * The role the menu filter is applied against.
     *
     * The legacy JSON mode has no login, so this used to be null and every filter became a
     * no-op: an operator of that mode could select command 6 and decline any transfer in the
     * store through the fraud service, which has no ownership rule to stop it. A fixed
     * customerId is a real customer row, so that operator is a CUSTOMER and gets the customer
     * menu. No identity is invented here - a mode with no users still has no analyst.
     */
    private UserRole effectiveRole() {
        if (currentUser != null) {
            return currentUser.role();
        }
        return customerId > 0 ? UserRole.CUSTOMER : null;
    }

    private int resolveCustomerId() {
        if (currentUser != null) {
            Integer cid = currentUser.customerId();
            if (cid == null) {
                throw AccessDeniedException.forRole("Current user is not a customer");
            }
            return cid;
        }
        if (customerId <= 0) {
            throw AccessDeniedException.forRole("No customer id available");
        }
        return customerId;
    }

    public void run() {
        loginIfNeeded();

        while (true) {
            System.out.println("\n=== Mini-bank (Domain Model) ===");

            UserRole role = effectiveRole();

            for (ConsoleCommand cmd : commands) {
                if (!cmd.isVisibleFor(role)) {
                    continue;
                }
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
                    .filter(c -> c.isVisibleFor(role))
                    .findFirst()
                    .orElse(null);

            if (cmd == null) {
                System.out.println("Invalid choice");
                continue;
            }

            try {
                cmd.execute();
            } catch (DataIntegrityException e) {
                // Ahead of the DomainException clause on purpose. Inconsistent stored data is
                // our fault, not the operator's: it must keep the ERROR severity and the
                // generic wording it had while these sites threw bare RuntimeExceptions,
                // rather than printing internal ids at WARN like an expected domain refusal.
                AppLogger.error(
                        "ui.console",
                        "Inconsistent stored data in command " + cmd.code(),
                        e
                );
                System.out.println("[Error] Operation could not be completed. Please try again.");
            } catch (DomainException e) {
                AppLogger.warn(
                        "ui.console",
                        "Domain error in command " + cmd.code() + ": " + e.getMessage(),
                        e
                );
                System.out.println("[Error] " + e.getMessage());
            } catch (Exception e) {
                AppLogger.error(
                        "ui.console",
                        "Unexpected error in command " + cmd.code(),
                        e
                );
                System.out.println("[Error] Operation could not be completed. Please try again.");
            }
        }
    }

    private void showCustomerAndAccounts() {
        int cid = resolveCustomerId();
        CustomerRepository customers = infra.customers;
        AccountRepository accounts = infra.accounts;

        var cust = customers.byId(cid).orElseThrow();
        System.out.println("\nCustomer: " + cust.name() + " (id=" + cust.id() + ")");
        System.out.println("Email: " + cust.email());
        System.out.println("Address: " + cust.address().street() + ", " + cust.address().city());
        System.out.println("Accounts:");
        var accs = accounts.byCustomerId(cid);
        for (Account a : accs) {
            System.out.println("  - id=" + a.id()
                    + ", IBAN=" + a.iban().value()
                    + ", balance=" + a.balance());
        }
        System.out.println("Beneficiaries:");
        for (Beneficiary b : cust.beneficiaries()) {
            System.out.println("  - id=" + b.id()
                    + ", " + b.name()
                    + ", IBAN=" + b.iban().value()
                    + ", trusted=" + b.trusted());
        }
    }

    private void addBeneficiary() {
        int cid = resolveCustomerId();
        CustomerRepository customers = infra.customers;

        // Reject an unknown customer before prompting, so the operator is not asked for
        // three answers that are then thrown away.
        customers.byId(cid).orElseThrow();

        // Read the console input before opening the unit of work. A JSON unit of work
        // holds the store lock for its whole life, and blocking on stdin while holding it
        // would freeze every other thread that touches the store.
        System.out.print("Beneficiary name: ");
        String name = in.nextLine().trim();

        System.out.print("IBAN (e.g., CZ2001000000000012345678): ");
        String iban = in.nextLine().trim();

        System.out.print("Trusted? (y/N): ");
        boolean trusted = in.nextLine().trim().equalsIgnoreCase("y");

        UnitOfWork uow = infra.uowFactory.begin();
        try (UowScope ignored = new UowScope(uow)) {
            var cust = customers.byId(cid).orElseThrow();

            int bid = customers.nextBeneficiaryId();
            Beneficiary b = new Beneficiary(bid, name, new IBAN(iban), trusted);

            customers.saveBeneficiary(cust.id(), b);

            uow.commit();
            System.out.println("[OK] Beneficiary added id=" + bid);
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    private void submitPaymentByBeneficiary() {
        int cid = resolveCustomerId();
        AccountRepository accounts = infra.accounts;

        var accs = accounts.byCustomerId(cid);
        if (accs.isEmpty()) {
            System.out.println("No accounts");
            return;
        }

        int accId = askInt("Source account id", accs.get(0).id());
        int benId = askInt("Beneficiary id", -1);
        double amount = askDouble("Amount CZK", 1000);

        int tid = services.transferService.submitPaymentByBeneficiary(cid, accId, benId, amount, "");
        System.out.println("[OK] Transfer created id=" + tid);
    }

    private void submitPaymentToIban() {
        int cid = resolveCustomerId();
        AccountRepository accounts = infra.accounts;

        var accs = accounts.byCustomerId(cid);
        if (accs.isEmpty()) {
            System.out.println("No accounts");
            return;
        }

        int accId = askInt("Source account id", accs.get(0).id());
        System.out.print("Target IBAN: ");
        String iban = in.nextLine().trim();
        double amount = askDouble("Amount CZK", 6000);

        int tid = services.transferService.submitPaymentToIban(cid, accId, iban, amount, "");
        System.out.println("[OK] Transfer created id=" + tid);
    }

    private void authorizePayment() {
        // Asked first, like commands 3 and 4, so a user with no customer id is refused by a
        // server-side rule before being prompted for a transfer id.
        int cid = resolveCustomerId();
        int tid = askInt("Transfer id", -1);
        System.out.print("OTP (0000/123456): ");
        String otp = in.nextLine().trim();

        // A wrong code is now a refusal rather than a quiet return. Caught here so that all
        // three attempts read the same way: without this the first two would print only an
        // error and the third - which declines the transfer and returns normally - would
        // still print the status and balance below.
        try {
            services.transferService.authorizePayment(cid, tid, otp);
        } catch (InvalidOtpException e) {
            System.out.println("[Error] Wrong one-time password.");
        }

        TransferRepository transfers = infra.transfers;
        AccountRepository accounts = infra.accounts;

        var t = transfers.byId(tid).orElseThrow();
        System.out.println("[Result] Transfer " + tid + " has status " + t.status());

        var acc = accounts.byId(t.sourceAccountId()).orElseThrow();
        System.out.println("Account id=" + acc.id() + " balance=" + acc.balance());
    }

    private void fraudMenu() {
        FraudAlertRepository alerts = infra.alerts;
        var list = alerts.all();
        if (list.isEmpty()) {
            System.out.println("No alerts");
            return;
        }

        System.out.println("Alerts:");
        for (FraudAlert a : list) {
            System.out.println("  - id=" + a.id()
                    + ", transfer=" + a.transferId()
                    + ", state=" + a.state()
                    + ", reason=" + a.reason());
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
        int cid = resolveCustomerId();
        int tid = askInt("Transfer id", -1);
        services.transferService.cancelPayment(cid, tid);

        TransferRepository transfers = infra.transfers;
        var t = transfers.byId(tid).orElseThrow();
        System.out.println("[Result] Transfer " + tid + " has status " + t.status());
    }

    private void listTransfersByAccount() {
        int cid = resolveCustomerId();
        AccountRepository accounts = infra.accounts;
        TransferRepository transfers = infra.transfers;

        var accs = accounts.byCustomerId(cid);
        if (accs.isEmpty()) {
            System.out.println("No accounts");
            return;
        }

        // A4: this reads an account id the operator typed straight out of the repository,
        // bypassing the application services, so OwnershipGuard cannot reach it from where it
        // lives. Either restrict the prompt to accs or route this through a guarded read.
        int accId = askInt("Account id", accs.get(0).id());
        var list = transfers.bySourceAccount(accId);
        if (list.isEmpty()) {
            System.out.println("No transfers");
            return;
        }

        for (Transfer t : list) {
            System.out.println("  - id=" + t.id()
                    + ", status=" + t.status()
                    + ", amount=" + t.amount()
                    + ", to=" + t.targetIbanSnapshot());
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
