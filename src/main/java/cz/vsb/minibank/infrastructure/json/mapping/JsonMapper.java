package cz.vsb.minibank.infrastructure.json.mapping;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.json.dto.*;
import cz.vsb.minibank.domain.lazy.LazyList;
import cz.vsb.minibank.domain.lazy.LazyRef;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.time.Instant;

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
        j.accountIds.addAll(c.accountIds());
        for (Beneficiary b : c.beneficiaries()) {
            j.beneficiaries.add(toDto(b));
        }
        return j;
    }

    public static Customer toDomain(JsonCustomer j) {
        Customer c = new Customer(j.id, j.name, j.email, toDomain(j.address));
        for (Integer id : j.accountIds) {
            c.addAccountId(id);
        }
        for (JsonBeneficiary jb : j.beneficiaries) {
            c.addBeneficiary(toDomain(jb));
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
            c.attachAccounts(new LazyList<>(() -> {
                UnitOfWork uow = UowContext.current();

                // Deferred: runs on whatever thread first calls Customer.accounts(), which
                // may be after the unit of work that loaded the customer has closed. It
                // therefore takes the store lock itself; inside an open unit of work the
                // acquisition is reentrant and free.
                return store.read(bundle -> bundle.accounts.stream()
                        .filter(a -> j.accountIds.contains(a.id))
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
        j.balance = a.balance().amount().doubleValue();
        j.dailyLimit = a.dailyLimit().amount().doubleValue();
        // Left absent rather than written as 0.0 when the account has no override: a stored
        // zero would mean "authorize every payment", which is a real and different rule.
        if (a.softDailyThreshold() != null) {
            j.softDailyThreshold = a.softDailyThreshold().amount().doubleValue();
        }
        return j;
    }

    public static Account toDomain(JsonAccount j) {
        // Guarded rather than handed straight to Money.czk. That method has a double overload,
        // so Money.czk(j.softDailyThreshold) would compile by autounboxing and throw a
        // NullPointerException on every account in every store written before this field
        // existed - which is all of them.
        Money soft = (j.softDailyThreshold != null) ? Money.czk(j.softDailyThreshold.doubleValue()) : null;
        // Account.version is deliberately not restored: the JSON backend has no version column
        // and nothing on this side reads one. See JsonAccount.
        return new Account(j.id, new IBAN(j.iban), Money.czk(j.balance), Money.czk(j.dailyLimit), soft);
    }

    // Transfer

    public static JsonTransfer toDto(Transfer t) {
        JsonTransfer j = new JsonTransfer();
        j.id = t.id();
        j.sourceAccountId = t.sourceAccountId();
        j.beneficiaryId = t.beneficiaryId();
        j.targetIbanSnapshot = t.targetIbanSnapshot();
        j.amount = t.amount().amount().doubleValue();
        j.currency = t.currency();
        // Absent rather than 0.0 on a transfer that has not settled, so "charged nothing" and
        // "not charged yet" survive the round trip as different values.
        if (t.fee() != null) {
            j.fee = t.fee().amount().doubleValue();
        }
        j.message = t.message();
        j.status = t.status().name();
        j.createdAt = t.createdAt().toString(); // write ISO-8601 string directly via Instant#toString
        if (t.settledAt() != null) {
            j.settledAt = t.settledAt().toString();
        }
        if (t.authMethod() != null) {
            j.authMethod = t.authMethod().method();
            if (t.authMethod() instanceof CardPayment cp) {
                j.cardNumberMasked = cp.cardNumberMasked();
            }
        }
        j.declineReason = t.declineReason();
        j.authAttempts = t.authAttempts();
        if (t.authValidUntil() != null) {
            j.authValidUntil = t.authValidUntil().toString();
        }

        return j;
    }

    public static Transfer toDomain(JsonTransfer j) {
        Transfer t = new Transfer(
                j.id, j.sourceAccountId, j.beneficiaryId, j.targetIbanSnapshot,
                Money.czk(j.amount), j.currency
        );

        // restore authMethod if it was present
        Payment payment = null;
        if (j.authMethod != null) {
            if ("CARD".equalsIgnoreCase(j.authMethod)) {
                payment = new CardPayment(Money.czk(j.amount), j.cardNumberMasked);
            } else {
                // generic fallback object for other auth methods
                payment = new Payment(j.authMethod, Money.czk(j.amount)) {
                };
            }
        }

        // parse createdAt if present
        Instant ts = null;
        try {
            if (j.createdAt != null) {
                ts = Instant.parse(j.createdAt);
            }
        } catch (Exception ignored) {
        }

        // parse OTP metadata
        Integer attempts = j.authAttempts;
        Instant validUntil = null;
        try {
            if (j.authValidUntil != null) {
                validUntil = Instant.parse(j.authValidUntil);
            }
        } catch (Exception ignored) {
        }

        // restore status without side effects
        try {
            var status = TransferStatus.valueOf(j.status);
            t.hydrateForLoad(status, payment, j.declineReason, ts, attempts, validUntil);
        } catch (Exception ignored) {
        }

        // After the block above, never inside it. That catch exists to tolerate one thing - a
        // stored status string that does not parse - and putting these calls inside it would
        // let an unparseable status also silently discard the fee and the settlement instant,
        // and let a bug in these lines silently reset every transfer in the file to CREATED.
        // JsonTransferRepository.parseInstantOrNull makes the same argument about createdAt.
        //
        // Both values are guarded: Money.czk has a double overload that would autounbox a null
        // fee into a NullPointerException, and Instant.parse(null) throws.
        Money fee = (j.fee != null) ? Money.czk(j.fee.doubleValue()) : null;
        Instant settledAt = null;
        try {
            if (j.settledAt != null) {
                settledAt = Instant.parse(j.settledAt);
            }
        } catch (Exception ignored) {
        }
        t.hydrateSettlement(fee, settledAt);
        t.attachMessage(j.message);

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
            // no unit of work bound when Transfer.sourceAccount() is dereferenced. The
            // identity-map probe is inside the hold too, because reading j.sourceAccountId
            // is a read of a Bundle-resident DTO.
            t.attachSourceAccount(new LazyRef<>(() -> store.read(bundle -> {
                UnitOfWork uow = UowContext.current();
                if (uow != null) {
                    Account cached = uow.get(Account.class, j.sourceAccountId);
                    if (cached != null) {
                        return cached;
                    }
                }

                JsonAccount accDto = bundle.accounts.stream()
                        .filter(a -> a.id == j.sourceAccountId)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Account not found: " + j.sourceAccountId));

                Account acc = JsonMapper.toDomain(accDto);

                if (uow != null) {
                    uow.put(Account.class, acc.id(), acc);
                }

                return acc;
            })));

            // Lazy beneficiary (if present)
            if (j.beneficiaryId != null) {
                t.attachBeneficiary(new LazyRef<>(() -> store.read(bundle -> {
                    UnitOfWork uow = UowContext.current();
                    if (uow != null) {
                        Beneficiary cached = uow.get(Beneficiary.class, j.beneficiaryId);
                        if (cached != null) {
                            return cached;
                        }
                    }

                    // Beneficiary is stored inside customers. Both passes over the nested
                    // beneficiaries list are one hold, so saveBeneficiary cannot insert
                    // between finding the customer and finding the beneficiary.
                    //
                    // This search is across every customer, which is why A3 deleted
                    // CustomerRepository.beneficiaryById. Here it is navigation from a
                    // transfer the caller already holds rather than an id the caller typed,
                    // and it has no production caller today. Dereferencing this ref is only
                    // safe once that transfer has been proved to be the caller's - which is
                    // A4's job, before TransferDetailsDto ever grows a beneficiary field.
                    JsonCustomer custDto = bundle.customers.stream()
                            .filter(c -> c.beneficiaries != null
                                    && c.beneficiaries.stream().anyMatch(b -> b.id == j.beneficiaryId))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "Customer for beneficiary " + j.beneficiaryId + " not found"));

                    JsonBeneficiary benDto = custDto.beneficiaries.stream()
                            .filter(b -> b.id == j.beneficiaryId)
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "Beneficiary not found: " + j.beneficiaryId));

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
        j.notes = a.notes();

        return j;
    }

    public static FraudAlert toDomain(JsonFraudAlert j) {
        FraudAlert a = new FraudAlert(j.id, j.transferId, j.reason);

        java.time.Instant ts = null;
        try {
            if (j.createdAt != null) {
                ts = java.time.Instant.parse(j.createdAt);
            }
        } catch (Exception ignored) {
        }

        java.util.List<String> tags =
                (j.tags != null) ? j.tags : java.util.Collections.emptyList();

        try {
            var st = FraudAlertState.valueOf(j.state);
            a.hydrateForLoad(
                    st,
                    j.reason,
                    ts,
                    j.riskScore,
                    j.assignee,
                    tags,
                    j.notes
            );
        } catch (Exception ignored) {
        }

        // Outside the catch above, for the reason the transfer mapper gives: that block is
        // there to tolerate an unparseable state string and nothing else. hydrateDecision takes
        // all three as null, which is what every alert written before these fields existed has.
        java.time.Instant resolvedAt = null;
        try {
            if (j.resolvedAt != null) {
                resolvedAt = java.time.Instant.parse(j.resolvedAt);
            }
        } catch (Exception ignored) {
        }
        a.hydrateDecision(j.decision, j.decidedBy, resolvedAt);

        return a;
    }

}
