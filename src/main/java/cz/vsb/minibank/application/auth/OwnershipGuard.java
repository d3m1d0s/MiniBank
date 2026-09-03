package cz.vsb.minibank.application.auth;

import cz.vsb.minibank.domain.customer.Account;
import cz.vsb.minibank.domain.customer.Beneficiary;
import cz.vsb.minibank.domain.customer.Customer;
import cz.vsb.minibank.domain.transfer.Transfer;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.NotFoundException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.application.payment.TransferApplicationService;

/**
 * Resolves the objects a request names and refuses the ones the calling customer does not own.
 *
 * It sits beside the application services rather than in the controllers because the console
 * UI and DemoRunner call those services directly; a check written into a controller covers
 * the HTTP callers only, which is how the guarded /me routes and the unguarded raw routes
 * came to disagree in the first place.
 *
 * Every method below takes the caller as a loaded {@link Customer} rather than as an id, so a
 * caller has to be resolved before anything it named can be. Ownership is answered from that
 * one aggregate: both backends already load accountIds and beneficiaries scoped to the
 * customer, so these are the scoped queries rather than a second copy of them.
 *
 * The property this buys is exactly this: the four money-moving methods of
 * {@link TransferApplicationService} cannot be entered without a caller id, and inside them
 * every caller-supplied id is resolved here. It is NOT the wider claim that no unscoped
 * lookup exists - AccountRepository.byId and TransferRepository.byId are still public and
 * unscoped, and are legitimately called with ids read out of our own rows.
 */
public final class OwnershipGuard {

    private final CustomerRepository customers;
    private final AccountRepository accounts;

    public OwnershipGuard(CustomerRepository customers, AccountRepository accounts) {
        this.customers = customers;
        this.accounts = accounts;
    }

    /**
     * Resolves the customer the caller is acting as.
     *
     * A session pointing at a customer the store no longer holds is a server-state fault, not
     * a refusal: the caller never named that id, so it is not a 404 for them to interpret.
     */
    public Customer requireCaller(int customerId) {
        return customers.byId(customerId)
                .orElseThrow(() -> new DataIntegrityException("Customer not found: " + customerId));
    }

    /**
     * Resolves an account the caller named.
     *
     * An account owned by somebody else is reported exactly like one that exists nowhere:
     * account ids are small consecutive integers, so a distinguishable answer here would let
     * a caller enumerate them.
     */
    public Account requireOwnedAccount(Customer caller, int accountId) {
        if (!caller.accountIds().contains(accountId)) {
            throw new NotFoundException("Account not found: " + accountId);
        }
        // Past this point the id came off our own customer row, so a miss is our fault.
        return accounts.byId(accountId)
                .orElseThrow(() -> new DataIntegrityException(
                        "Customer " + caller.id() + " owns missing account " + accountId));
    }

    /** Resolves a beneficiary from the caller's own address book; see requireOwnedAccount. */
    public Beneficiary requireOwnedBeneficiary(Customer caller, int beneficiaryId) {
        return caller.beneficiaries().stream()
                .filter(b -> b.id() == beneficiaryId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Beneficiary not found: " + beneficiaryId));
    }

    /**
     * Refuses a transfer that debits an account the caller does not own.
     *
     * A transfer row carries no customer of its own, so owning a transfer means owning the
     * account it debits. Ownership is therefore evaluated at call time against the current
     * owner of that account: if an account is ever moved to another customer, its pending
     * transfers move with it, so any such hand-over has to settle or decline them first.
     */
    public void requireOwnedTransfer(Customer caller, Transfer transfer) {
        if (!caller.accountIds().contains(transfer.sourceAccountId())) {
            throw new NotFoundException("Transfer not found: " + transfer.id());
        }
    }
}
