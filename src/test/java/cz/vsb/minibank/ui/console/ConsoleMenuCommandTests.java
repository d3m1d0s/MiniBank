package cz.vsb.minibank.ui.console;

import cz.vsb.minibank.application.AuthService;
import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.MinibankProperties;
import cz.vsb.minibank.application.Pbkdf2PasswordEncoder;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ConsoleMenuCommandTests {

    Path tempDir;
    String dataPath;
    Bootstrap infra;
    BootstrapServices app;
    int customerId;

    InputStream originalIn;
    PrintStream originalOut;

    @BeforeEach
    void setUp() throws IOException {
        // temporary JSON file for this test
        tempDir = Files.createTempDirectory("minibank-console-");
        dataPath = tempDir.resolve("data.json").toString();
        infra = new Bootstrap(dataPath);

        // initial data (same as in MinibankUowTests)
        customerId = infra.customers.nextId();
        Customer c = new Customer(
                customerId,
                "Test User",
                "test@example.com",
                new Address("Street 1", "City"),
                Money.czk(5000)
        );
        infra.customers.save(c);

        int accId = infra.accounts.nextId();
        Account a = new Account(
                accId,
                new IBAN("CZ6508000000192000145399"),
                Money.czk(20000)
        );
        infra.accounts.save(a);
        c.addAccountId(accId);
        infra.customers.save(c);

        // application services as in App
        app = new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory
        );

        // save original System.in / System.out
        originalIn = System.in;
        originalOut = System.out;
    }

    @AfterEach
    void tearDown() throws IOException {
        // restore original system streams
        System.setIn(originalIn);
        System.setOut(originalOut);

        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted((p1, p2) -> p2.compareTo(p1))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    void showCustomerAndAccounts_printsBasicInfo() throws Exception {
        // replace System.out
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));

        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);

        // invoke private method showCustomerAndAccounts via reflection
        var m = ConsoleMenu.class.getDeclaredMethod("showCustomerAndAccounts");
        m.setAccessible(true);
        m.invoke(menu);

        String output = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Customer: Test User"),
                "Customer name should be printed");
        assertTrue(output.contains("Accounts:"),
                "Accounts list header should be printed");
        assertTrue(output.contains("CZ6508000000192000145399"),
                "Account IBAN should be printed");
    }

    @Test
    void addBeneficiary_createsAndPersistsBeneficiary() throws Exception {
        // prepare console input: name, IBAN. There is no third prompt any more - trust is the
        // input the fraud rules key on, and now that an open alert is what stops money, a
        // customer who could set it could opt out of the review entirely.
        String consoleInput = String.join("\n",
                "Alice",
                "CZ1301000000000098765432"
        ) + "\n";

        ByteArrayInputStream in = new ByteArrayInputStream(
                consoleInput.getBytes(StandardCharsets.UTF_8));
        System.setIn(in);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));

        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);

        // invoke private method addBeneficiary()
        var m = ConsoleMenu.class.getDeclaredMethod("addBeneficiary");
        m.setAccessible(true);
        m.invoke(menu);

        String output = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[OK] Beneficiary added"),
                "Console should report successful beneficiary addition");

        // verify that data is actually persisted via UoW
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            Customer reloaded = infra.customers.byId(customerId).orElseThrow();
            assertEquals(1, reloaded.beneficiaries().size(),
                    "After addBeneficiary the customer should have one beneficiary");
            Beneficiary b = reloaded.beneficiaries().get(0);
            assertEquals("Alice", b.name());
            assertEquals("CZ1301000000000098765432", b.iban().value());
            assertFalse(b.trusted(),
                    "A beneficiary the customer added must not be trusted: trust is bank-set");
            scope.uow().commit();
        }
    }

    /**
     * Command 8 answers about whichever account id is typed, and account ids are small
     * consecutive integers starting at 100. Without the ownership check the signed-in customer
     * reads a stranger's payment history - id, status, amount and target IBAN - by counting up.
     */
    @Test
    void listTransfersByAccount_refusesAnAccountTheCustomerDoesNotOwn() throws Exception {
        final String strangerTargetIban = "CZ2001000000000012345678";

        int strangerId = infra.customers.nextId();
        Customer stranger = new Customer(
                strangerId,
                "Other User",
                "other@example.com",
                new Address("Street 2", "City"),
                Money.czk(5000)
        );
        infra.customers.save(stranger);

        int strangerAccId = infra.accounts.nextId();
        infra.accounts.save(new Account(
                strangerAccId,
                new IBAN("CZ1301000000000098765432"),
                Money.czk(50000)
        ));
        stranger.addAccountId(strangerAccId);
        infra.customers.save(stranger);

        infra.transfers.add(new Transfer(
                infra.transfers.nextId(),
                strangerAccId,
                null,
                strangerTargetIban,
                Money.czk(7777)
        ));

        // The menu builds its Scanner over System.in in a field initializer, so the input has
        // to be in place before the menu is constructed.
        ByteArrayInputStream in = new ByteArrayInputStream(
                (strangerAccId + "\n").getBytes(StandardCharsets.UTF_8));
        System.setIn(in);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));

        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);

        var m = ConsoleMenu.class.getDeclaredMethod("listTransfersByAccount");
        m.setAccessible(true);
        m.invoke(menu);

        String output = outContent.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains(strangerTargetIban),
                "another customer's target IBAN must not be printed: " + output);
        assertFalse(output.contains("7777"),
                "another customer's transfer amount must not be printed: " + output);
        assertTrue(output.contains("[Error]"),
                "an account the customer does not own should be refused: " + output);
    }

    @Test
    void run_invalidChoiceThenExit() {
        // first enter an invalid menu option, then 9 (exit)
        String consoleInput = "42\n9\n";
        ByteArrayInputStream in = new ByteArrayInputStream(
                consoleInput.getBytes(StandardCharsets.UTF_8));
        System.setIn(in);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));

        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);
        // if everything is OK, run() should finish and not hang
        menu.run();

        String output = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Invalid choice"),
                "Invalid choice should be handled with 'Invalid choice' message");
        assertTrue(output.contains("Bye!"),
                "When selecting 9 the menu should exit correctly");
    }

    /**
     * A script that stops before choosing 9, or a terminal where the operator presses Ctrl+D,
     * leaves the menu prompt with nothing to read. The read is outside the try that wraps the
     * commands, so an unguarded one throws NoSuchElementException out of run() and out of main
     * as a stack trace. Ending the session is the only sensible answer, and it has to be an
     * exit rather than another pass, since the input that ended never comes back.
     */
    @Test
    void run_returnsWhenInputRunsOutBeforeTheExitChoice() {
        // No 9. The invalid option forces one full pass through the loop, so the end of input
        // is met at the menu prompt itself and not before the loop was ever entered.
        String consoleInput = "42\n";
        ByteArrayInputStream in = new ByteArrayInputStream(
                consoleInput.getBytes(StandardCharsets.UTF_8));
        System.setIn(in);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));

        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);

        assertDoesNotThrow(() -> menu.run(),
                "input that runs out at the menu prompt must end the session, not throw");

        String output = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Invalid choice"),
                "the loop should have run one full pass before the input ended: " + output);
        assertFalse(output.contains("[Error]"),
                "a finished script is not an error and must not be reported as one: " + output);
    }

    /**
     * Same end of input one loop earlier. The login prompts run before the menu loop and so
     * before any handler at all, which makes an unguarded read there the first thing an empty
     * pipe hits in SQL mode.
     */
    @Test
    void run_returnsWhenInputRunsOutAtTheLoginPrompt() {
        AuthService authService = new AuthService(infra.users, new Pbkdf2PasswordEncoder());

        // Empty: the operator is asked for a username and the input is already over. No user
        // row is needed, because no login is ever attempted.
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        System.setIn(in);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));

        ConsoleMenu menu = new ConsoleMenu(app, infra, authService);

        assertDoesNotThrow(() -> menu.run(),
                "input that runs out at the login prompt must end the session, not throw");

        String output = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("=== Login ==="),
                "the login loop should have been entered and then abandoned: " + output);
        assertFalse(output.contains("Welcome,"),
                "nobody signed in, so no session should be announced: " + output);
    }

    /**
     * The username prompt is not the only read the login loop makes before any handler exists.
     * A script that names a user and stops ends one line later, at the password prompt.
     */
    @Test
    void run_returnsWhenInputRunsOutAtThePasswordPrompt() {
        AuthService authService = new AuthService(infra.users, new Pbkdf2PasswordEncoder());

        // A username and nothing after it. No login is attempted, so no user row is needed
        // and the test pays no hashing cost.
        ByteArrayInputStream in = new ByteArrayInputStream(
                "alice\n".getBytes(StandardCharsets.UTF_8));
        System.setIn(in);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));

        ConsoleMenu menu = new ConsoleMenu(app, infra, authService);

        assertDoesNotThrow(() -> menu.run(),
                "input that runs out at the password prompt must end the session, not throw");

        String output = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Password: "),
                "the username was consumed, so the password prompt should have been reached: "
                        + output);
        assertFalse(output.contains("Welcome,"),
                "no password was ever given, so nobody signed in: " + output);
    }

    /**
     * A mistyped number used to end the command. NumberFormatException is neither of the two
     * exceptions the menu names, so it reached the generic clause: the operator's typo was
     * recorded at ERROR with a stack trace and the whole command was abandoned. The value is
     * asked for again instead.
     */
    @Test
    void askInt_reAsksAfterATypoInsteadOfAbandoningTheCommand() throws Exception {
        final String ownTargetIban = "CZ2001000000000012345678";
        int ownAccId = infra.accounts.byCustomerId(customerId).get(0).id();

        infra.transfers.add(new Transfer(
                infra.transfers.nextId(),
                ownAccId,
                null,
                ownTargetIban,
                Money.czk(4242)
        ));

        // A typo, then the answer meant all along. Both lines are consumed only if the prompt
        // comes back; without the loop the first one throws out of the command and the second
        // is never read.
        ByteArrayInputStream in = new ByteArrayInputStream(
                ("twelve\n" + ownAccId + "\n").getBytes(StandardCharsets.UTF_8));
        System.setIn(in);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));

        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);

        var m = ConsoleMenu.class.getDeclaredMethod("listTransfersByAccount");
        m.setAccessible(true);
        assertDoesNotThrow(() -> { m.invoke(menu); },
                "a mistyped number must be asked again, not thrown out of the command");

        String output = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("twelve"),
                "the rejected answer should be quoted back so the operator sees what was read: "
                        + output);
        assertTrue(output.contains(ownTargetIban),
                "the number typed after the typo should be the one the command used: " + output);
    }

    /**
     * The prompts with no default are the ones an operator has no other way out of, and until
     * now the way out was an accident: Enter produced parseInt("") and the resulting
     * NumberFormatException aborted the command. That escape has to survive the re-asking loop,
     * and it has to stop being reported as a fault of the bank's - it was written into
     * minibank.log at ERROR, with a stack trace, beside the AUDIT records.
     */
    @Test
    void run_blankLineAtAPromptWithNoDefaultCancelsTheCommandQuietly() throws IOException {
        Path logPath = tempDir.resolve("minibank.log");
        String previousLogFile = System.getProperty(MinibankProperties.LOG_FILE);
        // Surefire points the whole suite at target/; this test needs a file only it writes to.
        System.setProperty(MinibankProperties.LOG_FILE, logPath.toString());

        // Command 7 asks for a transfer id and nothing else, the blank line cancels it, and 9
        // ends the session: the script never runs out, which now would end the session by
        // itself and prove nothing.
        String consoleInput = "7\n\n9\n";
        ByteArrayInputStream in = new ByteArrayInputStream(
                consoleInput.getBytes(StandardCharsets.UTF_8));
        System.setIn(in);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));

        try {
            ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);
            menu.run();
        } finally {
            if (previousLogFile == null) {
                System.clearProperty(MinibankProperties.LOG_FILE);
            } else {
                System.setProperty(MinibankProperties.LOG_FILE, previousLogFile);
            }
        }

        String output = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Cancelled."),
                "a blank line should abandon the command and say so: " + output);
        assertFalse(output.contains("Operation could not be completed"),
                "cancelling is not a failed operation and must not be announced as one: "
                        + output);
        assertTrue(output.contains("Bye!"),
                "the menu should have come back and taken the exit choice: " + output);

        String log = Files.exists(logPath) ? Files.readString(logPath) : "";
        assertFalse(log.contains("ERROR"),
                "an operator cancelling a command is not an error worth logging: " + log);
        assertFalse(log.contains("NumberFormatException"),
                "no stack trace belongs in the file that carries the audit records: " + log);
    }

    /**
     * The other half of the same prompt behaviour: where a default is offered, Enter still
     * takes it. The re-asking loop must not have turned an empty line into a cancel everywhere.
     */
    @Test
    void askInt_emptyLineStillTakesTheOfferedDefault() throws Exception {
        final String ownTargetIban = "CZ1301000000000098765432";
        int ownAccId = infra.accounts.byCustomerId(customerId).get(0).id();

        infra.transfers.add(new Transfer(
                infra.transfers.nextId(),
                ownAccId,
                null,
                ownTargetIban,
                Money.czk(1234)
        ));

        // "Account id" defaults to the customer's first account, which is the only one there is.
        ByteArrayInputStream in = new ByteArrayInputStream(
                "\n".getBytes(StandardCharsets.UTF_8));
        System.setIn(in);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));

        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);

        var m = ConsoleMenu.class.getDeclaredMethod("listTransfersByAccount");
        m.setAccessible(true);
        m.invoke(menu);

        String output = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains(ownTargetIban),
                "Enter at a prompt that offers a default should answer with it: " + output);
    }
}
