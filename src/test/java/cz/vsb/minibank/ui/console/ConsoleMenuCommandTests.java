package cz.vsb.minibank.ui.console;

import cz.vsb.minibank.application.BootstrapServices;
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
                new Address("Street 1", "City")
        );
        infra.customers.save(c);

        int accId = infra.accounts.nextId();
        Account a = new Account(
                accId,
                new IBAN("CZ6508000000192000145399"),
                Money.czk(20000),
                Money.czk(5000)
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
}
