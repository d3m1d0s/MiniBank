package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;

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

    public TransferApplicationService(CustomerRepository customers,
                                      AccountRepository accounts,
                                      TransferRepository transfers,
                                      FraudAlertRepository alerts,
                                      FeePolicy feePolicy,
                                      RiskService riskService,
                                      OtpValidator otpValidator) {
        this.customers = customers;
        this.accounts = accounts;
        this.transfers = transfers;
        this.alerts = alerts;
        this.feePolicy = feePolicy;
        this.riskService = riskService;
        this.otpValidator = otpValidator;
        this.beneficiaryResolver = new BeneficiaryResolver(customers);
    }

    /** UC 04 – Submit Payment Order (příjemce z adresáře) */
    public int submitPaymentByBeneficiary(int customerId, int sourceAccountId, int beneficiaryId, double amountCzk, String message) {
        var customer = customers.byId(customerId).orElseThrow(() -> new RuntimeException("Customer not found"));
        var account = accounts.byId(sourceAccountId).orElseThrow(() -> new RuntimeException("Account not found"));
        var beneficiary = customers.beneficiaryById(beneficiaryId).orElseThrow(() -> new RuntimeException("Beneficiary not found"));

        Money amount = Money.czk(amountCzk);
        boolean trusted = beneficiary.trusted();
        RiskDecision decision = riskService.evaluate(trusted, amount, account.dailyLimit());

        int id = transfers.nextId();
        Transfer t = new Transfer(id, account.id(), beneficiary.id(), beneficiary.iban().value(), amount, "CZK");

        routeTransferCreation(customer, account, t, decision);
        return id;
    }

    /** UC 04 – Submit Payment Order (přímo na IBAN, bez uloženého příjemce) */
    public int submitPaymentToIban(int customerId, int sourceAccountId, String targetIban, double amountCzk, String message) {
        // validace IBAN (vyhodí IllegalArgumentException při chybě)
        IBAN iban = new IBAN(targetIban);
        var customer = customers.byId(customerId).orElseThrow(() -> new RuntimeException("Customer not found"));
        var account = accounts.byId(sourceAccountId).orElseThrow(() -> new RuntimeException("Account not found"));

        Money amount = Money.czk(amountCzk);
        boolean trusted = false; // nový/neupravený příjemce není důvěryhodný
        RiskDecision decision = riskService.evaluate(trusted, amount, account.dailyLimit());

        int id = transfers.nextId();
        Transfer t = new Transfer(id, account.id(), null, iban.value(), amount, "CZK");

        routeTransferCreation(customer, account, t, decision);
        return id;
    }

    private void routeTransferCreation(Customer customer, Account account, Transfer t, RiskDecision decision) {
        // include: Check Funds (nepřímo v Account.debit při send)
        if (!decision.requireAuthorization() && !decision.createFraudAlert()) {
            // přímé odeslání
            t.send(account, feePolicy);
            transfers.add(t);
            account.registerTransfer(t.id());
            accounts.save(account);
        } else {
            // WAITING_AUTH + případný FraudAlert
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

    /** UC 05 – Authorize Payment */
    public void authorizePayment(int transferId, String otp) {
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
    }

    /** UC 19 – Cancel Payment Order */
    public void cancelPayment(int transferId) {
        var t = transfers.byId(transferId).orElseThrow(() -> new RuntimeException("Transfer not found"));
        if (t.status() == TransferStatus.SENT) throw new RuntimeException("Cannot cancel already SENT transfer");
        t.decline("Canceled by customer");
        transfers.save(t);
    }

    /** Pomocník: nalezení příjemce pro IBAN v adresáři zákazníka */
    static class BeneficiaryResolver {
        private final CustomerRepository customers;
        BeneficiaryResolver(CustomerRepository customers) { this.customers = customers; }
        // případné rozšíření: mapování IBAN -> Beneficiary
    }
}