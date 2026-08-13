package cz.vsb.minibank.infrastructure.sql.repo;

import cz.vsb.minibank.domain.exceptions.ConflictException;

import java.sql.SQLException;

/**
 * What a failed write out of this package means, decided in one place.
 *
 * Every save in these five repositories answered a SQLException with a bare RuntimeException, and
 * RestExceptionHandler's catch-all reports that as 500 INTERNAL_ERROR. For one class of failure
 * that is the wrong answer. The schema carries UNIQUE constraints as its last line of defence -
 * accounts.iban, users.username, and fraud_alerts_one_per_transfer, whose own schema comment says
 * it exists because the second alert creation site is guarded by a read rather than by a lock, so
 * a race could file two - and tripping one of them is the store refusing a write, not breaking on
 * it. Nothing is wrong with the server, the transaction rolled back whole, and this project's
 * error contract answers a refusal like that with 409.
 *
 * SQLSTATE 23505 alone, deliberately. A not-null (23502), a foreign key (23503) or a CHECK (23514)
 * says the row contradicts an invariant the domain was supposed to hold before the statement was
 * built; the caller cannot make that right by looking again, and calling it a conflict would send
 * them to try. Those keep the 500 they have, and so does every other state: a code this does not
 * name behaves exactly as it did.
 *
 * Written once rather than five times over, because the same check copied into each repository is
 * five chances for one of them to drift. A file of its own rather than a second class inside one
 * of the five, because a reader who meets the call in any of the other four has to be able to find
 * the rule by its name. It is package-private, so nothing above the SQL adapter can reach for it.
 *
 * ConflictException itself, and not a descendant of its own. The descendants that carry a handler
 * of their own exist because each owes its caller a different message, and this refusal owes none:
 * what they asked to store is already there, which is what the generic conflict body already says.
 * That type's ordering rule holds on every path that can reach here - the one violation an HTTP
 * caller can provoke is the second alert, on a transfer authorizePayment resolved through the
 * ownership guard before it read the alert at all, and the other two constraints are written only
 * by seeding, which no request drives.
 *
 * The message names the aggregate and its id and stops there. It must not carry the constraint
 * name or the colliding value, because ConsoleMenu prints the message of any DomainException
 * straight to its operator; on the REST side no message reaches the wire at all, since every body
 * there is built from the ApiErrors catalogue. The driver's own exception is kept as the cause: it
 * is the only remaining record of which constraint refused, and a cause is rendered nowhere a
 * caller can see.
 */
final class SqlWriteFailure {

    /**
     * The SQLSTATE PostgreSQL raises for a unique or primary key violation. Every upsert behind
     * this helper names ON CONFLICT (id), so the primary key half is already absorbed and what
     * reaches here is one of the secondary UNIQUE constraints. The one write that is not an upsert
     * - the batched ownership UPDATE in SqlCustomerRepository - sets a column nothing keeps
     * unique, which is why reading the state off the exception handed in is enough and the
     * batch's own chain of causes need not be walked.
     */
    private static final String UNIQUE_VIOLATION = "23505";

    private SqlWriteFailure() {
    }

    /**
     * The exception a save owes its caller for a driver failure: a conflict when the store refused
     * the row, and otherwise the wrapped RuntimeException these repositories have always thrown,
     * carrying the message they have always written.
     */
    static RuntimeException forSave(SQLException e, String kind, int id) {
        if (!UNIQUE_VIOLATION.equals(e.getSQLState())) {
            return new RuntimeException("Failed to save " + kind + " id=" + id, e);
        }

        // Attached rather than passed in: this family of exceptions takes a message and no cause.
        ConflictException refused = new ConflictException(
                "Cannot store " + kind + " " + id
                        + ": another row already holds a value this one must not repeat");
        refused.initCause(e);
        return refused;
    }
}
