package cz.vsb.minibank.ui.console;

import cz.vsb.minibank.domain.UserRole;


/**
 * Command pattern for console actions.
 * Each command represents one user operation in the console UI.
 */
public interface ConsoleCommand {
    /** Menu code (e.g. "1", "2", "5"...) the user types. */
    String code();

    /** Human-readable description shown in the menu. */
    String description();

    /** Execute the command. All I/O and service calls happen here. */
    void execute();

    /**
     * For RBAC: by default a command is visible to all roles.
     */
    default boolean isVisibleFor(UserRole role) {
        return true;
    }
}
