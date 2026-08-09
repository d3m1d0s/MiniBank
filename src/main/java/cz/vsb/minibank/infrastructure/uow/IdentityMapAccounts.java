package cz.vsb.minibank.infrastructure.uow;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.value.IBAN;

import java.util.List;
import java.util.Optional;

/**
 * The identity-map half of an account lookup by IBAN, written once for both backends.
 *
 * {@code byId} can probe the map before it touches the store, because the caller already
 * holds the key the map is built on. {@code byIban} cannot, and that gap is what this closes.
 * It is one rule and it has to answer the same on JSON and on SQL, so it lives here rather
 * than as two copies inside two repositories that would drift.
 */
public final class IdentityMapAccounts {

    private IdentityMapAccounts() {
    }

    /**
     * Returns the account this unit of work already holds for the IBAN, if any.
     *
     * Two cases, and only the first is a cache. An account the transaction has already loaded
     * must be answered from the map so that one row means one instance: a second instance
     * built from the same row would be registered over the first, and every later {@code byId}
     * would return the copy that does not carry the pending mutation. An account the
     * transaction has *created* has no row at all until commit, so the store cannot answer for
     * it, and reporting it as absent would send a payment to an account of this bank out to
     * the external network.
     *
     * @param uow the current unit of work, or null when there is none
     * @throws DataIntegrityException when two accounts in flight claim the same IBAN
     */
    public static Optional<Account> byIban(UnitOfWork uow, IBAN iban) {
        if (uow == null) {
            return Optional.empty();
        }

        List<Account> matches = uow.all(Account.class).stream()
                .filter(a -> a.iban().equals(iban))
                .toList();

        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            throw new DataIntegrityException("IBAN " + iban.value() + " is held by "
                    + matches.size() + " accounts in one unit of work: "
                    + matches.stream().map(a -> String.valueOf(a.id())).toList());
        }
        return Optional.of(matches.get(0));
    }
}
