package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.domain.exceptions.InsufficientFundsException;
import cz.vsb.minibank.domain.value.Money;

import java.util.Objects;

/**
 * Application service for payment related use cases such as submitting, authorizing and canceling transfers.
 */
public class TransferApplicationService {

    public static final int MAX_OTP_ATTEMPTS = 3;
    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final BeneficiaryResolver beneficiaryResolver;
    private final TransferRepository transfers;
    private final FraudAlertRepository alerts;
    private final FeePolicy feePolicy;
    private final RiskService riskService;
    private final OtpValidator otpValidator;
    private final PaymentNetworkGateway paymentNetworkGateway;
    private final UnitOfWorkFactory uowFactory;

    public TransferApplicationService(CustomerRepository customers,
                                      AccountRepository accounts,
                                      TransferRepository transfers,
                                      FraudAlertRepository alerts,
                                      FeePolicy feePolicy,
                                      RiskService riskService,
                                      OtpValidator otpValidator,
                                      PaymentNetworkGateway paymentNetworkGateway,
                                      UnitOfWorkFactory uowFactory) {
        this.customers = customers;
        this.accounts = accounts;
        this.transfers = transfers;
        this.alerts = alerts;
        this.feePolicy = feePolicy;
        this.riskService = riskService;
        this.otpValidator = otpValidator;
        this.beneficiaryResolver = new BeneficiaryResolver(customers);
        this.paymentNetworkGateway = paymentNetworkGateway;
        this.uowFactory = uowFactory;
    }

    /**
     * UC 04 - Submit Payment Order to a saved beneficiary.
     *
     * @return identifier of the created transfer
     */
    public int submitPaymentByBeneficiary(int customerId, int sourceAccountId, int beneficiaryId, double amountCzk, String message) {
        // Validated before the unit of work opens: a rejected amount is caller input, not a
        // reason to start a transaction and roll it back.
        Money amount = Money.czkPayment(amountCzk);

        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {

            var customer = customers.byId(customerId).orElseThrow(() -> new RuntimeException("Customer not found"));
            var account = accounts.byId(sourceAccountId).orElseThrow(() -> new RuntimeException("Account not found"));
            var beneficiary = customers.beneficiaryById(beneficiaryId).orElseThrow(() -> new RuntimeException("Beneficiary not found"));

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

    /**
     * UC 04 - Submit Payment Order to an arbitrary IBAN.
     * The IBAN is validated by the value object and invalid input results in an exception.
     *
     * @return identifier of the created transfer
     */
    public int submitPaymentToIban(int customerId, int sourceAccountId, String targetIban, double amountCzk, String message) {
        // Validated before the unit of work opens: a rejected amount is caller input, not a
        // reason to start a transaction and roll it back.
        Money amount = Money.czkPayment(amountCzk);

        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            IBAN iban = new IBAN(targetIban);
            var customer = customers.byId(customerId).orElseThrow(() -> new RuntimeException("Customer not found"));
            var account = accounts.byId(sourceAccountId).orElseThrow(() -> new RuntimeException("Account not found"));

            boolean trusted = false;
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

    /**
     * Routes transfer creation based on risk decision by either sending immediately or requiring authorization and an optional fraud alert.
     */
    private void routeTransferCreation(Customer customer, Account account, Transfer t, RiskDecision decision) {

        Money fee = t.feeAmount(feePolicy);
        if (!account.canDebit(t.amount(), fee)) {
            throw new InsufficientFundsException("Insufficient funds");
        }

        if (!decision.requireAuthorization() && !decision.createFraudAlert()) {
            t.send(account, feePolicy);
            transfers.add(t);
            account.registerTransfer(t.id());
            accounts.save(account);

            paymentNetworkGateway.send(t);

        } else {
            t.requestAuthorization(new CardPayment(t.amount(), "****0000"));
            transfers.add(t);
            account.registerTransfer(t.id());
            accounts.save(account);

            if (decision.createFraudAlert()) {
                int aid = alerts.nextId();
                int riskScore = decision.riskScore();

                FraudAlert a = new FraudAlert(
                        aid,
                        t.id(),
                        Objects.requireNonNullElse(decision.reason(), "Suspicious"),
                        riskScore,
                        null,
                        null,
                        null
                );
                alerts.add(a);
            }
        }
    }

    /**
     * UC 05 - Authorize Payment.
     */
    public void authorizePayment(int transferId, String otp) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            var t = transfers.byId(transferId)
                    .orElseThrow(() -> new RuntimeException("Transfer not found"));

            var acc = accounts.byId(t.sourceAccountId())
                    .orElseThrow(() -> new RuntimeException("Source account not found"));

            if (t.status() != TransferStatus.WAITING_AUTH) {
                throw new RuntimeException("Transfer is not waiting for authorization");
            }

            if (t.isAuthExpired()) {
                t.decline("Authorization window expired");
                transfers.save(t);
                uow.commit();
                return;
            }

            boolean valid = otpValidator.isValid(transferId, otp);
            if (!valid) {
                t.registerFailedOtpAttempt(MAX_OTP_ATTEMPTS);
                transfers.save(t);
                uow.commit();
                return;
            }

            t.send(acc, feePolicy);

            accounts.save(acc);
            transfers.save(t);
            paymentNetworkGateway.send(t);

            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /**
     * UC 19 - Cancel Payment Order if it has not been sent yet.
     */
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

    /**
     * Helper for resolving beneficiary information from the customer's address book.
     */
    static class BeneficiaryResolver {
        private final CustomerRepository customers;

        BeneficiaryResolver(CustomerRepository customers) { this.customers = customers; }
    }
}
