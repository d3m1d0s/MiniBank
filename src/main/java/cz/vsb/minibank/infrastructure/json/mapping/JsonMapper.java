package cz.vsb.minibank.infrastructure.json.mapping;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.json.dto.*;

import java.time.Instant;

public class JsonMapper {
    // Address
    public static JsonAddress toDto(Address a) { JsonAddress j = new JsonAddress(); j.street = a.street(); j.city = a.city(); return j; }
    public static Address toDomain(JsonAddress j) { return new Address(j.street, j.city); }

    // Beneficiary
    public static JsonBeneficiary toDto(Beneficiary b) { JsonBeneficiary j = new JsonBeneficiary(); j.id=b.id(); j.name=b.name(); j.iban=b.iban().value(); j.trusted=b.trusted(); return j; }
    public static Beneficiary toDomain(JsonBeneficiary j) { return new Beneficiary(j.id, j.name, new IBAN(j.iban), j.trusted); }

    // Customer
    public static JsonCustomer toDto(Customer c) {
        JsonCustomer j = new JsonCustomer(); j.id=c.id(); j.name=c.name(); j.email=c.email(); j.address = toDto(c.address());
        j.accountIds.addAll(c.accountIds());
        for (Beneficiary b : c.beneficiaries()) j.beneficiaries.add(toDto(b));
        return j;
    }
    public static Customer toDomain(JsonCustomer j) {
        Customer c = new Customer(j.id, j.name, j.email, toDomain(j.address));
        for (Integer id : j.accountIds) c.addAccountId(id);
        for (JsonBeneficiary jb : j.beneficiaries) c.addBeneficiary(toDomain(jb));
        return c;
    }

    // Account
    public static JsonAccount toDto(Account a) {
        JsonAccount j = new JsonAccount(); j.id=a.id(); j.iban=a.iban().value();
        j.balance=a.balance().amount().doubleValue(); j.dailyLimit=a.dailyLimit().amount().doubleValue();
        j.transferIds.addAll(a.transferIds());
        return j;
    }
    public static Account toDomain(JsonAccount j) {
        Account a = new Account(j.id, new IBAN(j.iban), Money.czk(j.balance), Money.czk(j.dailyLimit));
        for (Integer t : j.transferIds) a.registerTransfer(t);
        return a;
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
        j.status = t.status().name();
        j.createdAt = t.createdAt().toString(); // write ISO-8601 string directly via Instant#toString
        if (t.authMethod() != null) {
            j.authMethod = t.authMethod().method();
            if (t.authMethod() instanceof CardPayment cp) j.cardNumberMasked = cp.cardNumberMasked();
        }
        j.declineReason = t.declineReason();
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
                payment = new Payment(j.authMethod, Money.czk(j.amount)) { };
            }
        }

        // parse createdAt if present
        java.time.Instant ts = null;
        try {
            if (j.createdAt != null) ts = java.time.Instant.parse(j.createdAt);
        } catch (Exception ignored) {}

        // restore status without side effects
        try {
            var status = TransferStatus.valueOf(j.status);
            t.hydrateForLoad(status, payment, j.declineReason, ts);
        } catch (Exception ignored) {}

        return t;
    }

    // FraudAlert
    public static JsonFraudAlert toDto(FraudAlert a) {
        JsonFraudAlert j = new JsonFraudAlert(); j.id=a.id(); j.transferId=a.transferId(); j.state=a.state().name(); j.reason=a.reason(); j.createdAt=a.createdAt().toString(); return j; }
    public static FraudAlert toDomain(JsonFraudAlert j) {
        FraudAlert a = new FraudAlert(j.id, j.transferId, j.reason);
        // parse createdAt if present
        java.time.Instant ts = null;
        try {
            if (j.createdAt != null) ts = java.time.Instant.parse(j.createdAt);
        } catch (Exception ignored) {}
        // restore state without side effects
        try {
            var st = FraudAlertState.valueOf(j.state);
            a.hydrateForLoad(st, j.reason, ts);
        } catch (Exception ignored) {}
        return a;
    }
}
