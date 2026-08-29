package cz.vsb.minibank.infrastructure.json.mapping;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.json.dto.*;
import cz.vsb.minibank.domain.lazy.LazyList;
import cz.vsb.minibank.domain.lazy.LazyRef;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.StoredValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Utility class for mapping between domain objects and their JSON DTO representations.
 */
public class JsonMapper {

    // Address

    public static JsonAddress toDto(Address a) {
        JsonAddress j = new JsonAddress();
        j.street = a.street();
        j.city = a.city();
        return j;
    }

    public static Address toDomain(JsonAddress j) {
        return new Address(j.street, j.city);
    }

    // Beneficiary

    public static JsonBeneficiary toDto(Beneficiary b) {
        JsonBeneficiary j = new JsonBeneficiary();
        j.id = b.id();
        j.name = b.name();
        j.iban = b.iban().value();
        j.trusted = b.trusted();
        return j;
    }

    public static Beneficiary toDomain(JsonBeneficiary j) {
        return new Beneficiary(j.id, j.name, new IBAN(j.iban), j.trusted);
    }

    // Customer

    public static JsonCustomer toDto(Customer c) {
        JsonCustomer j = new JsonCustomer();
        j.id = c.id();
        j.name = c.name();
        j.email = c.email();
        j.address = toDto(c.address());
        j.dailyLimit = c.dailyLimit().amount();
        // Left absent rather than written as 0.00 when the customer has no tier of their own: a
        // stored zero would mean "authorize every payment", which is a real and different rule.
        if (c.softDailyThreshold() != null) {
            j.softDailyThreshold = c.softDailyThreshold().amount();
        }
        j.accountIds.addAll(c.accountIds());
        for (Beneficiary b : c.beneficiaries()) {
            j.beneficiaries.add(toDto(b));
        }
        return j;
    }

    /**
     * The nulls guarded here are not hypothetical, and the field initializers on
     * {@link JsonCustomer} do not stop them: Jackson replaces an initialized list with null on an
     * explicit {@code "accountIds": null}, and this store is a file people open and edit.
     *
     * Absent reads as empty rather than as corrupt because that is already how the rest of the
     * codebase reads it - JsonCustomerRepository skips such a customer when searching by account
     * id, and JsonDataStore skips it when handing out the next beneficiary id. What neither of
     * them does is answer with a bare NullPointerException, which is what this loader did: an
     * unexplained 500 for a row every one of its neighbours can read.
     *
     * A missing address becomes an empty one for the reason the other backend has no choice about:
     * a customer whose street and city are NULL still arrives from SQL as an Address holding
     * nulls, never as a null Address, and every reader of {@code Customer.address()} dereferences
     * it without checking.
     */
    public static Customer toDomain(JsonCustomer j) {
        Address address = (j.address != null) ? toDomain(j.address) : new Address(null, null);

        // The daily ceiling is required and the soft tier is not, which is the same split the
        // account's balance and its own tier used to make and for the same reasons: a customer
        // with no ceiling is a customer nothing bounds, while a customer with no tier of their own
        // uses the bank-wide one and a stored 0.00 would be the opposite rule.
        Money soft = (j.softDailyThreshold != null) ? Money.czk(j.softDailyThreshold) : null;

        Customer c = new Customer(j.id, j.name, j.email, address,
                requiredMoney(j.dailyLimit, "dailyLimit", "customer", j.id),
                soft);
        if (j.accountIds != null) {
            for (Integer id : j.accountIds) {
                c.addAccountId(id);
            }
        }
        if (j.beneficiaries != null) {
            for (JsonBeneficiary jb : j.beneficiaries) {
                c.addBeneficiary(toDomain(jb));
            }
        }
        return c;
    }

    /**
     * Converts a JSON customer to a domain customer and attaches lazy account loading
     * backed by the provided JsonDataStore and current UnitOfWork.
     */
    public static Customer toDomain(JsonCustomer j, JsonDataStore store) {
        // basic mapping
        Customer c = toDomain(j);

        if (store != null) {
            // Which accounts this customer owns is decided here, when the customer is built, and
            // not when the closure below runs. The DTO is not a stable object: a save puts a
            // freshly built JsonCustomer into the bundle in place of this one, so a customer
            // loaded earlier holds a reference to an instance the store no longer keeps. Reading
            // the list through that reference answers whatever the detached instance happens to
            // say, and it would answer the store's current list again the day a writer appended to
            // an existing DTO in place rather than replacing it - the same customer object giving
            // one answer or another depending on which kind of writer touched the row last.
            //
            // Only the ids are frozen. The accounts they name are still resolved from the live
            // bundle inside the closure, which is the whole point of a lazy list: the balances a
            // caller reads through it are the current ones, not the ones stored when the customer
            // was loaded.
            List<Integer> ownedAccountIds =
                    (j.accountIds != null) ? List.copyOf(j.accountIds) : List.of();

            c.attachAccounts(new LazyList<>(() -> {
                UnitOfWork uow = UowContext.current();

                // Deferred: runs on whatever thread first calls Customer.accounts(), which
                // may be after the unit of work that loaded the customer has closed. It
                // therefore takes the store lock itself; inside an open unit of work the
                // acquisition is reentrant and free.
                return store.read(bundle -> bundle.accounts.stream()
                        .filter(a -> ownedAccountIds.contains(a.id))
                        .map(dto -> {
                            if (uow != null) {
                                Account cached = uow.get(Account.class, dto.id);
                                if (cached != null) {
                                    return cached;
                                }
                            }
                            Account acc = JsonMapper.toDomain(dto);
                            if (uow != null) {
                                uow.put(Account.class, acc.id(), acc);
                            }
                            return acc;
                        })
                        .toList());
            }));
        }

        return c;
    }

    // Account

    public static JsonAccount toDto(Account a) {
        JsonAccount j = new JsonAccount();
        j.id = a.id();
        j.iban = a.iban().value();
        j.balance = a.balance().amount();
        return j;
    }

    public static Account toDomain(JsonAccount j) {
        // Account.version is deliberately not restored: the JSON backend has no version column
        // and nothing on this side reads one. See JsonAccount.
        return new Account(j.id, new IBAN(j.iban),
                requiredMoney(j.balance, "balance", "account", j.id));
    }

    /**
     * Reads a money field that a stored row must carry, refusing the row when it does not.
     *
     * These fields were primitive doubles until money moved to {@link java.math.BigDecimal}, and a
     * primitive has no absent value: an account written without a {@code balance} key came back
     * with a balance of 0.00 and nothing anywhere said so. Refusing the row is the same choice
     * {@code Transfer}'s constructor already makes about a non-positive stored amount, and for the
     * same reason - a corrupt store must be refused rather than loaded into the domain.
     */
    private static Money requiredMoney(BigDecimal stored, String field, String kind, int id) {
        return requiredMoney(stored, "CZK", field, kind, id);
    }

    /**
     * The same, for a field whose currency the row stores beside it rather than implies.
     *
     * Only a transfer does. An account's balance and a customer's two limits answer to columns
     * named balance_czk, daily_limit_czk and soft_daily_threshold_czk on the other backend, so
     * their currency is in the name and there is nothing stored to read. A transfer's is a stored
     * value, and it is read back here rather than forced to crowns so that a row written in
     * anything else arrives at {@code Transfer}'s constructor as what it claims to be and is
     * refused there. Forcing it is how this backend used to load a foreign row as real crowns
     * while the SQL one rebuilt it faithfully - one row answering differently depending on which
     * adapter read it.
     */
    private static Money requiredMoney(BigDecimal stored, String currency,
                                       String field, String kind, int id) {
        if (stored == null) {
            throw new DataIntegrityException(
                    "Stored " + kind + " " + id + " has no " + field);
        }
        if (currency == null) {
            throw new DataIntegrityException(
                    "Stored " + kind + " " + id + " has no currency");
        }
        return Money.of(currency, stored);
    }

    // Transfer

    public static JsonTransfer toDto(Transfer t) {
        JsonTransfer j = new JsonTransfer();
        j.id = t.id();
        j.sourceAccountId = t.sourceAccountId();
        j.beneficiaryId = t.beneficiaryId();
        j.targetIbanSnapshot = t.targetIbanSnapshot();
        j.amount = t.amount().amount();
        j.currency = t.amount().currency();
        // Absent rather than 0.00 on a transfer that has not settled, so "charged nothing" and
        // "not charged yet" survive the round trip as different values.
        if (t.fee() != null) {
            j.fee = t.fee().amount();
        }
        j.message = t.message();
        j.status = t.status().name();
        j.createdAt = t.createdAt().toString(); // write ISO-8601 string directly via Instant#toString
        if (t.settledAt() != null) {
            j.settledAt = t.settledAt().toString();
        }
        // Absent rather than a constant meaning "owes nothing", on the same terms as the fee
        // above: null is a real value here and the common one, and inventing a name for it would
        // put a decision in the store about every row written before this field existed.
        if (t.dispatchState() != null) {
            j.dispatchState = t.dispatchState().name();
        }
        if (t.authMethod() != null) {
            j.authMethod = t.authMethod().method();
            if (t.authMethod() instanceof CardPayment cp) {
                j.cardNumberMasked = cp.cardNumberMasked();
            }
        }
        j.declineReason = t.declineReason();
        // Written beside the sentence it belongs with, and left absent where there is no instant,
        // on the same terms as settledAt above.
        if (t.declinedAt() != null) {
            j.declinedAt = t.declinedAt().toString();
        }
        j.authAttempts = t.authAttempts();
        if (t.authValidUntil() != null) {
            j.authValidUntil = t.authValidUntil().toString();
        }

        return j;
    }

    public static Transfer toDomain(JsonTransfer j) {
        Money amount = requiredMoney(j.amount, j.currency, "amount", "transfer", j.id);

        Transfer t = new Transfer(
                j.id, j.sourceAccountId, j.beneficiaryId, j.targetIbanSnapshot,
                amount
        );

        // restore authMethod if it was present
        Payment payment = null;
        if (j.authMethod != null) {
            if ("CARD".equalsIgnoreCase(j.authMethod)) {
                payment = new CardPayment(amount, j.cardNumberMasked);
            } else {
                // generic fallback object for other auth methods
                payment = new Payment(j.authMethod, amount) {
                };
            }
        }

        Instant ts = StoredValue.requiredInstant(j.createdAt, "creation instant", "transfer", j.id);

        Integer attempts = j.authAttempts;

        // An absent deadline is a real value and stays one: a transfer released from review waits
        // with no clock running, and Transfer.isAuthExpired reads null as exactly that. Which is
        // why an unreadable deadline must not also arrive as null. On a row still WAITING_AUTH it
        // would silently turn the customer's five minutes into an unlimited window, and the next
        // save would write the null over the string that caused it.
        Instant validUntil = StoredValue.presentInstantOrNull(
                j.authValidUntil, "authorization deadline", "transfer", j.id);

        TransferStatus status = StoredValue.requiredEnum(
                TransferStatus.class, j.status, "status", "transfer", j.id);
        t.hydrateForLoad(status, payment, j.declineReason, ts, attempts, validUntil);

        // Absent is a real value for both of these, unlike the status above: a transfer that has
        // not settled was charged nothing and moved no money. The fee is guarded rather than
        // passed straight through because Money.czk has a double overload that would autounbox a
        // null into a NullPointerException. Absent is all that is tolerated, though: an unreadable
        // settlement instant used to become null here and then be written back as null by toDto,
        // so the day the money actually left was lost from the store and not only from the object,
        // and a row with no settlement instant is counted against its creation day instead.
        Money fee = (j.fee != null) ? Money.czk(j.fee) : null;
        Instant settledAt = StoredValue.presentInstantOrNull(
                j.settledAt, "settlement instant", "transfer", j.id);
        t.hydrateSettlement(fee, settledAt);
        t.attachMessage(j.message);

        // Read through the same helper as the settlement instant, and for the same reason: absent
        // is a real value here - everything not refused, and every refused row stored before this
        // field - while a timestamp that is present and will not parse is a row no loader should
        // accept. Reading a garbled one as null would write the null back over it on the next
        // save, losing the only record of when a payment was stopped.
        Instant declinedAt = StoredValue.presentInstantOrNull(
                j.declinedAt, "refusal instant", "transfer", j.id);
        t.hydrateDeclinedAt(declinedAt);

        // The same split the two instants above make, on an enum: absent is a real value, present
        // and unreadable is not. It is written out here rather than through StoredValue.requiredEnum
        // alone because that method refuses a null, which is right for the status - a transfer
        // must have one - and wrong here, where a null says this payment owes the network nothing.
        // Reading a garbled name as null would be the lenient answer, and lenient here means a
        // payment that has left the bank silently stops being one the sweep will ever dispatch.
        DispatchState dispatchState = (j.dispatchState != null)
                ? StoredValue.requiredEnum(DispatchState.class, j.dispatchState,
                        "dispatch state", "transfer", j.id)
                : null;
        t.hydrateDispatch(dispatchState);

        return t;
    }

    /**
     * Converts a JSON transfer to a domain transfer and wires lazy navigation
     * for source account and beneficiary using the JsonDataStore and UnitOfWork.
     */
    public static Transfer toDomain(JsonTransfer j, JsonDataStore store) {
        // basic mapping (status, authMethod, createdAt)
        Transfer t = toDomain(j);

        if (store != null) {
            // Lazy source account
            // Deferred, same as the LazyList above: locks for itself because there may be
            // no unit of work bound when Transfer.sourceAccount() is dereferenced. Which
            // account it points at is captured here for the reason given there - a save
            // replaces the whole JsonTransfer in the bundle, so this DTO need not still be the
            // stored one when the closure runs - and the account itself is still read live.
            // The identity-map probe stays inside the hold so that it and the scan below decide
            // against one state of the store.
            int sourceAccountId = j.sourceAccountId;
            t.attachSourceAccount(new LazyRef<>(() -> store.read(bundle -> {
                UnitOfWork uow = UowContext.current();
                if (uow != null) {
                    Account cached = uow.get(Account.class, sourceAccountId);
                    if (cached != null) {
                        return cached;
                    }
                }

                JsonAccount accDto = bundle.accounts.stream()
                        .filter(a -> a.id == sourceAccountId)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Account not found: " + sourceAccountId));

                Account acc = JsonMapper.toDomain(accDto);

                if (uow != null) {
                    uow.put(Account.class, acc.id(), acc);
                }

                return acc;
            })));

            // Lazy beneficiary (if present)
            // Captured like the source account above, and here the capture also settles the
            // unboxing: the guard proves the id is present once, at load time, where the closure
            // used to trust a field that a later generation of this DTO could have left null.
            if (j.beneficiaryId != null) {
                int beneficiaryId = j.beneficiaryId;
                t.attachBeneficiary(new LazyRef<>(() -> store.read(bundle -> {
                    UnitOfWork uow = UowContext.current();
                    if (uow != null) {
                        Beneficiary cached = uow.get(Beneficiary.class, beneficiaryId);
                        if (cached != null) {
                            return cached;
                        }
                    }

                    // Beneficiary is stored inside customers. Both passes over the nested
                    // beneficiaries list are one hold, so saveBeneficiary cannot insert
                    // between finding the customer and finding the beneficiary.
                    //
                    // This search is across every customer, which is why the ownership pass deleted
                    // CustomerRepository.beneficiaryById. Here it is navigation from a
                    // transfer the caller already holds rather than an id the caller typed,
                    // and it has no production caller today. Dereferencing this ref is only
                    // safe once that transfer has been proved to be the caller's - which is
                    // the read-your-own-data rule, before TransferDetailsDto ever grows a beneficiary field.
                    JsonCustomer custDto = bundle.customers.stream()
                            .filter(c -> c.beneficiaries != null
                                    && c.beneficiaries.stream().anyMatch(b -> b.id == beneficiaryId))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "Customer for beneficiary " + beneficiaryId + " not found"));

                    JsonBeneficiary benDto = custDto.beneficiaries.stream()
                            .filter(b -> b.id == beneficiaryId)
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "Beneficiary not found: " + beneficiaryId));

                    Beneficiary b = JsonMapper.toDomain(benDto);

                    if (uow != null) {
                        uow.put(Beneficiary.class, b.id(), b);
                    }

                    return b;
                })));
            }
        }

        return t;
    }

    // FraudAlert

    public static JsonFraudAlert toDto(FraudAlert a) {
        JsonFraudAlert j = new JsonFraudAlert();
        j.id = a.id();
        j.transferId = a.transferId();
        j.state = a.state().name();
        j.decision = a.decision();
        j.decidedBy = a.decidedBy();
        j.decisionComment = a.decisionComment();
        j.reason = a.reason();
        if (a.createdAt() != null) {
            j.createdAt = a.createdAt().toString();
        }
        if (a.resolvedAt() != null) {
            j.resolvedAt = a.resolvedAt().toString();
        }

        j.riskScore = a.riskScore();
        j.assignee = a.assignee();
        if (a.tags() != null) {
            j.tags.addAll(a.tags());
        }

        // notes is deliberately not written. The journal is a list of its own beside the alerts,
        // and the field on this record survives only so a store written before the journal
        // existed can be read once and carried; see JsonFraudAlert.notes.

        return j;
    }

    public static FraudAlert toDomain(JsonFraudAlert j) {
        java.time.Instant ts =
                StoredValue.requiredInstant(j.createdAt, "creation instant", "fraud alert", j.id);

        java.util.List<String> tags =
                (j.tags != null) ? j.tags : java.util.Collections.emptyList();

        // The comment an analyst gave with a verdict used to be appended into the reason behind
        // a " | ", so a record written before it had a field of its own carries both facts on one
        // line. Split here, which is this backend's half of what the SQL migration does with an
        // UPDATE: the head is the sentence the rules produced and the tail is what a person wrote.
        // The separator was written by one line of code and the rules' own sentences contain no
        // bar, so the split is exact rather than a guess, and a record already carrying its own
        // comment is left alone.
        String reason = j.reason;
        String comment = j.decisionComment;
        if (comment == null && reason != null) {
            int bar = reason.indexOf(" | ");
            if (bar >= 0) {
                comment = reason.substring(bar + 3);
                reason = reason.substring(0, bar);
            }
        }

        FraudAlert a = new FraudAlert(j.id, j.transferId, reason);

        FraudAlertState st = StoredValue.requiredEnum(
                FraudAlertState.class, j.state, "state", "fraud alert", j.id);
        a.hydrateForLoad(st, reason, ts, j.riskScore, j.assignee, tags);

        // hydrateDecision takes all four as null, which is what every alert written before
        // these fields existed has. Absent is a real value here, unlike the state above; a
        // resolution instant that is present and cannot be read is not, because it used to land
        // on exactly the value a legal row carries and nothing downstream could tell the two
        // apart.
        java.time.Instant resolvedAt = StoredValue.presentInstantOrNull(
                j.resolvedAt, "resolution instant", "fraud alert", j.id);
        a.hydrateDecision(j.decision, j.decidedBy, resolvedAt, comment);

        return a;
    }

}
