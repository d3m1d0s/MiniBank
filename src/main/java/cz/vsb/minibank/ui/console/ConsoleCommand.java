package cz.vsb.minibank.ui.console;

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
}
