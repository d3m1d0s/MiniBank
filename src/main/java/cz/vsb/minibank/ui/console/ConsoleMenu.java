package cz.vsb.minibank.ui.console;

import cz.vsb.minibank.application.AuthService;
import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.exceptions.AccessDeniedException;
import cz.vsb.minibank.domain.exceptions.AuthenticationFailedException;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.InvalidOtpException;
import cz.vsb.minibank.domain.exceptions.TransferUnderReviewException;
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

        /**
         * Whether an operator in this role may see this command and run it.
         *
         * Read on the dispatch path as well as the display one, so it is an access check and
         * not a formatting rule.
         *
         * A command that names no role is open to anybody, an unknown role included: that is
         * what "no restriction" means. A command that does name roles is closed to an unknown
         * one. That second line used to return true, which is the wrong direction for a check
         * like this - it made "we do not know who you are" mean "you may do anything",
         * including the fraud menu.
         *
         * Not reachable today: {@code effectiveRole} answers null only when there is no signed
         * in user and no positive customer id, and nothing constructs the menu that way. A
         * default that fails open is one caller away from mattering, which is why it is the
         * default that changed rather than the caller.
         */
        @Override
        public boolean isVisibleFor(UserRole role) {
            if (allowedRoles == null || allowedRoles.length == 0) {
                return true;
            }
            if (role == null) {
                return false;
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
            // End of input is an end of session, not a failure. This loop runs before the
            // menu's try block and there is no handler anywhere above it, so an unguarded
            // read here turns Ctrl+D, or piped input that simply stops, into a stack trace
            // out of main. Giving up on the login is the whole answer: run() then reaches the
            // menu loop, whose own guard ends it on the same exhausted input, and an operator
            // who never signed in has no role, so that menu offers nothing but Exit.
            //
            // Returning and not continuing. The input that ended does not come back, so a
            // second pass would redraw this header forever.
            if (!in.hasNextLine()) {
                return;
            }
            String username = in.nextLine().trim();

            System.out.print("Password: ");
            // The same end of session one read later, and the likelier half of it: a script
            // that names a user and stops. Guarding only the read above would move the stack
            // trace down a line rather than remove it.
            if (!in.hasNextLine()) {
                return;
            }
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
            // Input that runs out without choosing 9 leaves the menu with nothing to read.
            // This read sits outside the try below, so the NoSuchElementException Scanner
            // answers with would leave run() and main() as a stack trace; worse, when the
            // end of input first lands inside a command the generic clause prints "could not
            // be completed" and logs at ERROR, and then this line crashes anyway, so the
            // operator gets a misleading message and a trace for what is only a finished
            // script. Returning is the exit the missing 9 would have caused, and it has to be
            // a return: nothing more will ever arrive, so continuing would spin on the menu.
            if (!in.hasNextLine()) {
                return;
            }
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
            } catch (CommandCancelled e) {
                // Deliberately unlogged. The operator changed their mind at a prompt; the file
                // that would record it is the one carrying the AUDIT records, and a menu that
                // was never used is not an event worth keeping.
                System.out.println("Cancelled.");
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

        // Never prompted for. Beneficiary.trusted is the input the fraud rules key on, so a
        // customer who could set it could opt out of both the authorization threshold and the
        // alert - and now that an alert is what stops money, that would be a customer-settable
        // authorization bypass rather than merely a skipped OTP. It is a bank-set attribute;
        // the demo dataset is the only writer of `true`.
        boolean trusted = false;

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            var cust = customers.byId(cid).orElseThrow();

            int bid = customers.nextBeneficiaryId();
            Beneficiary b = new Beneficiary(bid, name, new IBAN(iban), trusted);

            customers.saveBeneficiary(cust.id(), b);

            scope.uow().commit();
            System.out.println("[OK] Beneficiary added id=" + bid);
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
        int benId = askInt("Beneficiary id");
        double amount = askDouble("Amount CZK", 1000);

        int tid = services.transferService.submitPaymentByBeneficiary(cid, accId, benId, amount, "").transferId();
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

        int tid = services.transferService.submitPaymentToIban(cid, accId, iban, amount, "").transferId();
        System.out.println("[OK] Transfer created id=" + tid);
    }

    private void authorizePayment() {
        // Asked first, like commands 3 and 4, so a user with no customer id is refused by a
        // server-side rule before being prompted for a transfer id.
        int cid = resolveCustomerId();
        int tid = askInt("Transfer id");
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
        } catch (TransferUnderReviewException e) {
            // Caught here rather than left to the menu's DomainException clause so the operator
            // still gets the status and balance readout below. Nothing was spent and nothing
            // moved; the transfer is simply waiting on an analyst.
            System.out.println("[Error] This payment is being reviewed by the bank."
                    + " It can be confirmed once the review is finished, or cancelled.");
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
            // The transfer's status, not only the alert's: an operator deciding an alert has to
            // be able to see whether the money is still held or has already gone. '?' rather
            // than an exception because a dangling alert is possible on the JSON backend and a
            // listing must not fail on one.
            String transferStatus = infra.transfers.byId(a.transferId())
                    .map(t -> t.status().name())
                    .orElse("?");
            System.out.println("  - id=" + a.id()
                    + ", transfer=" + a.transferId()
                    + " (" + transferStatus + ")"
                    + ", state=" + a.state()
                    + ", reason=" + a.reason());
        }

        System.out.print("Action (approve/decline/request): ");
        String act = in.nextLine().trim();
        int tid = askInt("Transfer id");

        switch (act.toLowerCase()) {
            case "approve" -> {
                services.fraudService.approve(tid);
                System.out.println("[OK] Alert cleared. Transfer " + tid
                        + " is released for the customer to confirm; no money has moved.");
            }
            case "decline" -> {
                System.out.print("Reason: ");
                String reason = in.nextLine().trim();
                services.fraudService.decline(tid, reason.isEmpty() ? "Declined" : reason);
                var t = infra.transfers.byId(tid).orElseThrow();
                System.out.println("[OK] Alert marked suspicious. Transfer " + tid
                        + " has status " + t.status()
                        + (t.status() == TransferStatus.SENT
                        ? " - the payment had already been sent and was not reversed."
                        : "."));
            }
            // Prints a line because the call is now a no-op on both aggregates: without one the
            // operator would see a menu redraw and no evidence that anything happened.
            case "request" -> {
                services.fraudService.requestCustomerConfirmation(tid);
                System.out.println("[OK] Alert left open and the transfer left as it was."
                        + " The customer's confirmation step is what 'approve' unlocks,"
                        + " and the console carries no notes to record.");
            }
            default -> System.out.println("Unknown action");
        }
    }

    private void cancelPayment() {
        int cid = resolveCustomerId();
        int tid = askInt("Transfer id");
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

        int accId = askInt("Account id", accs.get(0).id());

        // The typed id has to be one of this customer's own accounts. This read goes straight to
        // the repository rather than through the application services, so OwnershipGuard is not
        // on the path and there is nowhere else for the rule to live. Somebody else's account and
        // one that does not exist get the same answer: account ids are small consecutive
        // integers, and any difference between those two replies enumerates the bank.
        if (accs.stream().noneMatch(a -> a.id() == accId)) {
            System.out.println("[Error] You have no account with id " + accId + ".");
            return;
        }

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

    /**
     * The operator's way out of a prompt: abandon this command and go back to the menu.
     *
     * Carries no stack trace and no message. It is control flow, not a failure, and the frames
     * it would record are the console's own - nothing may ever print them next to the AUDIT
     * records in minibank.log, which is exactly what used to happen when the escape was a
     * NumberFormatException.
     */
    private static final class CommandCancelled extends RuntimeException {
        CommandCancelled() {
            super(null, null, false, false);
        }
    }

    /**
     * Prompts and reads one line, with end of input treated as a cancel.
     *
     * The guard is what makes the re-asking loops below safe to write. Without it a finished
     * script or a Ctrl+D meets {@code nextLine()} and gets NoSuchElementException, which is
     * neither exception the menu names and so lands in the generic clause: an ERROR line and a
     * trace for input that simply ended. The menu loop's own guard then ends the session on the
     * next pass, which is the right outcome; this only stops it being reported as a fault.
     */
    private String askLine(String prompt) {
        System.out.print(prompt);
        if (!in.hasNextLine()) {
            throw new CommandCancelled();
        }
        return in.nextLine().trim();
    }

    /**
     * Reads a whole number the operator has to supply, re-asking until one arrives. A blank
     * line cancels the command.
     *
     * The cancel is half of the fix and not a garnish. These four prompts have no default, and
     * before the re-asking loop existed the only way out of one was to type something
     * unparsable and let the NumberFormatException abort the command back to the menu - a
     * typo, or an Enter, recorded at ERROR with a stack trace in the file that carries the
     * AUDIT records, under the misleading "Operation could not be completed". Re-asking
     * without offering a way out would have taken that escape away and held the operator at a
     * prompt with no exit, so both halves land together.
     *
     * The cancel travels as an exception rather than as a sentinel return value for two
     * reasons. Every int is a legal answer here, so a sentinel would have to steal one: the
     * obvious candidate is the -1 these sites used to pass, and an operator who typed -1 would
     * then be cancelling by accident. And an unwind says the one thing that is true at the four
     * call sites - there is no value, so there is nothing to carry on with - where a sentinel
     * would put the same "if it is the magic number, return" check at each of them and leave
     * every one of those reads looking as if it produced an id.
     */
    private int askInt(String label) {
        while (true) {
            String s = askLine(label + " (blank to cancel): ");
            if (s.isEmpty()) {
                throw new CommandCancelled();
            }
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                System.out.println("[Error] '" + s + "' is not a whole number."
                        + " Type a number, or leave the line blank to cancel.");
            }
        }
    }

    /**
     * The same read where the prompt carries a default, which an empty line still answers with.
     * Only the typo behaviour changes: it is re-asked here rather than abandoning the command.
     */
    private int askInt(String label, int defVal) {
        while (true) {
            String s = askLine(label + " [" + defVal + "]: ");
            if (s.isEmpty()) {
                return defVal;
            }
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                System.out.println("[Error] '" + s + "' is not a whole number."
                        + " Type a number, or leave the line blank for " + defVal + ".");
            }
        }
    }

    private double askDouble(String label, double defVal) {
        while (true) {
            String s = askLine(label + " [" + defVal + "]: ");
            if (s.isEmpty()) {
                return defVal;
            }
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                System.out.println("[Error] '" + s + "' is not an amount."
                        + " Type a number, or leave the line blank for " + defVal + ".");
            }
        }
    }
}
