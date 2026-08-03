package cz.vsb.minibank.ui.console;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleMenuRolesTest {

    Path tempDir;
    String dataPath;
    Bootstrap infra;
    BootstrapServices app;
    int customerId;

    @BeforeEach
    void setUp() throws Exception {
        // temporary JSON file for this test
        tempDir = Files.createTempDirectory("minibank-roles-");
        dataPath = tempDir.resolve("data.json").toString();
        infra = new Bootstrap(dataPath);

        // minimal data so that ConsoleMenu(app, infra, customerId) can be created
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

        app = new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted((p1, p2) -> p2.compareTo(p1))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    @Test
    void customerRoleSeesOnlyCustomerCommands() throws Exception {
        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);

        var field = ConsoleMenu.class.getDeclaredField("commands");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ConsoleCommand> commands = (List<ConsoleCommand>) field.get(menu);

        Set<String> visibleForCustomer = commands.stream()
                .filter(c -> c.isVisibleFor(UserRole.CUSTOMER))
                .map(ConsoleCommand::code)
                .collect(Collectors.toSet());

        assertTrue(visibleForCustomer.contains("1"));
        assertTrue(visibleForCustomer.contains("2"));
        assertTrue(visibleForCustomer.contains("3"));
        assertTrue(visibleForCustomer.contains("4"));
        assertTrue(visibleForCustomer.contains("5"));
        assertTrue(visibleForCustomer.contains("7"));
        assertTrue(visibleForCustomer.contains("8"));

        // fraud analyst menu must not be visible for customer
        assertFalse(visibleForCustomer.contains("6"));
    }

    /**
     * An operator whose role is unknown gets nothing that names a role.
     *
     * This check is read on the dispatch path, not only when the menu is drawn, so it decides
     * what can be run and not merely what can be seen. It used to answer true for a null role,
     * which made "we do not know who you are" mean "you may do anything" - the fraud menu
     * included. Nothing constructs the menu that way today; the default is what changed, so
     * that nothing has to.
     */
    @Test
    void anUnknownRoleSeesNothingThatNamesARole() throws Exception {
        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);

        var field = ConsoleMenu.class.getDeclaredField("commands");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ConsoleCommand> commands = (List<ConsoleCommand>) field.get(menu);

        Set<String> visibleToAnUnknownRole = commands.stream()
                .filter(c -> c.isVisibleFor(null))
                .map(ConsoleCommand::code)
                .collect(Collectors.toSet());

        assertTrue(visibleToAnUnknownRole.isEmpty(),
                "every command in this menu names a role, so an unknown role must see none of"
                        + " them; saw " + visibleToAnUnknownRole);
    }

    @Test
    void fraudAnalystRoleSeesOnlyFraudCommands() throws Exception {
        ConsoleMenu menu = new ConsoleMenu(app, infra, customerId);

        var field = ConsoleMenu.class.getDeclaredField("commands");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ConsoleCommand> commands = (List<ConsoleCommand>) field.get(menu);

        Set<String> visibleForFraud = commands.stream()
                .filter(c -> c.isVisibleFor(UserRole.FRAUD_ANALYST))
                .map(ConsoleCommand::code)
                .collect(Collectors.toSet());

        assertTrue(visibleForFraud.contains("6"),
                "Fraud analyst should see fraud menu command");

        assertFalse(visibleForFraud.contains("1"));
        assertFalse(visibleForFraud.contains("2"));
        assertFalse(visibleForFraud.contains("3"));
        assertFalse(visibleForFraud.contains("4"));
        assertFalse(visibleForFraud.contains("5"));
        assertFalse(visibleForFraud.contains("7"));
        assertFalse(visibleForFraud.contains("8"));
    }
}
