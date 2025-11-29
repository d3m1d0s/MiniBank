package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.util.Objects;

public class TransferApplicationService {
    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final BeneficiaryResolver beneficiaryResolver;
    private final TransferRepository transfers;
    private final FraudAlertRepository alerts;
    private final FeePolicy feePolicy;
    private final RiskService riskService;
    private final OtpValidator otpValidator;
    private final UnitOfWorkFactory uowFactory;

    public TransferApplicationService(CustomerRepository customers,
                                      AccountRepository accounts,
                                      TransferRepository transfers,
                                      FraudAlertRepository alerts,
                                      FeePolicy feePolicy,
                                      RiskService riskService,
                                      OtpValidator otpValidator,
                                      UnitOfWorkFactory uowFactory) {
        this.customers = customers;
        this.accounts = accounts;
        this.transfers = transfers;
        this.alerts = alerts;
        this.feePolicy = feePolicy;
        this.riskService = riskService;
        this.otpValidator = otpValidator;
        this.beneficiaryResolver = new BeneficiaryResolver(customers);
        this.uowFactory = uowFactory;
    }

    /** UC 04 – Submit Payment Order (to a saved beneficiary). */
    public int submitPaymentByBeneficiary(int customerId, int sourceAccountId, int beneficiaryId, double amountCzk, String message) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {

            var customer = customers.byId(customerId).orElseThrow(() -> new RuntimeException("Customer not found"));
            var account = accounts.byId(sourceAccountId).orElseThrow(() -> new RuntimeException("Account not found"));
            var beneficiary = customers.beneficiaryById(beneficiaryId).orElseThrow(() -> new RuntimeException("Beneficiary not found"));

            Money amount = Money.czk(amountCzk);
            boolean trusted = beneficiary.trusted();
            RiskDecision decision = riskService.evaluate(trusted, amount, account.dailyLimit());

            int id = transfers.nextId();
            Transfer t = new Transfer(id, account.id(), beneficiary.id(), beneficiary.iban().value(), amount, "CZK");

            routeTransferCreation(customer, account, t, decision);
            uow.commit();
            return id;
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /** UC 04 – Submit Payment Order (to an arbitrary IBAN). */
    public int submitPaymentToIban(int customerId, int sourceAccountId, String targetIban, double amountCzk, String message) {
        // IBAN validation (throws IllegalArgumentException on invalid input)
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            IBAN iban = new IBAN(targetIban);
            var customer = customers.byId(customerId).orElseThrow(() -> new RuntimeException("Customer not found"));
            var account = accounts.byId(sourceAccountId).orElseThrow(() -> new RuntimeException("Account not found"));

            Money amount = Money.czk(amountCzk);
            boolean trusted = false; // a new/unknown recipient is not trusted
            RiskDecision decision = riskService.evaluate(trusted, amount, account.dailyLimit());

            int id = transfers.nextId();
            Transfer t = new Transfer(id, account.id(), null, iban.value(), amount, "CZK");

            routeTransferCreation(customer, account, t, decision);
            uow.commit();
            return id;
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    private void routeTransferCreation(Customer customer, Account account, Transfer t, RiskDecision decision) {
        // include: Check Funds (indirectly enforced in Account.debit during send)
        if (!decision.requireAuthorization() && !decision.createFraudAlert()) {
            // direct send
            t.send(account, feePolicy);
            transfers.add(t);
            account.registerTransfer(t.id());
            accounts.save(account);
        } else {
            // WAITING_AUTH and an optional FraudAlert
            t.requestAuthorization(new CardPayment(t.amount(), "****0000"));
            if (decision.createFraudAlert()) {
                int aid = alerts.nextId();
                FraudAlert a = new FraudAlert(aid, t.id(), Objects.requireNonNullElse(decision.reason(), "Suspicious"));
                alerts.add(a);
            }
            transfers.add(t);
            account.registerTransfer(t.id());
            accounts.save(account);
        }
    }

    /** UC 05 – Authorize Payment. */
    public void authorizePayment(int transferId, String otp) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            var t = transfers.byId(transferId).orElseThrow(() -> new RuntimeException("Transfer not found"));
            var acc = accounts.byId(t.sourceAccountId()).orElseThrow(() -> new RuntimeException("Source account not found"));
            if (!otpValidator.isValid(transferId, otp)) {
                t.decline("OTP failed");
                transfers.save(t);
                return;
            }
            t.send(acc, feePolicy);
            transfers.save(t);
            accounts.save(acc);
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /** UC 19 – Cancel Payment Order. */
    public void cancelPayment(int transferId) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            var t = transfers.byId(transferId).orElseThrow(() -> new RuntimeException("Transfer not found"));
            if (t.status() == TransferStatus.SENT) throw new RuntimeException("Cannot cancel an already SENT transfer");
            t.decline("Canceled by customer");
            transfers.save(t);
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /** Helper: resolves a recipient for a given IBAN within the customer's address book. */
    static class BeneficiaryResolver {
        private final CustomerRepository customers;
        BeneficiaryResolver(CustomerRepository customers) { this.customers = customers; }
        // possible extension: map IBAN -> Beneficiary
    }
}
