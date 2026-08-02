package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.domain.exceptions.ConflictException;
import cz.vsb.minibank.domain.exceptions.InsufficientFundsException;
import cz.vsb.minibank.domain.exceptions.InvalidOtpException;
import cz.vsb.minibank.domain.exceptions.NotFoundException;
import cz.vsb.minibank.domain.value.Money;

import java.util.Objects;

/**
 * Application service for payment related use cases such as submitting, authorizing and canceling transfers.
 */
public class TransferApplicationService {

    public static final int MAX_OTP_ATTEMPTS = 3;
    private final AccountRepository accounts;
    private final OwnershipGuard guard;
    private final TransferRepository transfers;
    private final FraudAlertRepository alerts;
    private final FeePolicy feePolicy;
    private final RiskService riskService;
    private final OtpValidator otpValidator;
    private final PaymentNetworkGateway paymentNetworkGateway;
    private final UnitOfWorkFactory uowFactory;

    public TransferApplicationService(AccountRepository accounts,
                                      TransferRepository transfers,
                                      FraudAlertRepository alerts,
                                      FeePolicy feePolicy,
                                      RiskService riskService,
                                      OtpValidator otpValidator,
                                      PaymentNetworkGateway paymentNetworkGateway,
                                      UnitOfWorkFactory uowFactory,
                                      OwnershipGuard guard) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.alerts = alerts;
        this.feePolicy = feePolicy;
        this.riskService = riskService;
        this.otpValidator = otpValidator;
        this.paymentNetworkGateway = paymentNetworkGateway;
        this.uowFactory = uowFactory;
        this.guard = guard;
    }

    /**
     * UC 04 - Submit Payment Order to a saved beneficiary.
     *
     * @return identifier of the created transfer
     * @throws NotFoundException when this caller has no such account or no such beneficiary,
     *         whether because none exists or because it is somebody else's
     */
    public int submitPaymentByBeneficiary(int callerCustomerId, int sourceAccountId, int beneficiaryId, double amountCzk, String message) {
        // Validated before the unit of work opens: a rejected amount is caller input, not a
        // reason to start a transaction and roll it back.
        Money amount = Money.czkPayment(amountCzk);

        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {

            // callerCustomerId comes from the session; the other two are caller input and are
            // resolved only against what that customer owns.
            var caller = guard.requireCaller(callerCustomerId);
            var account = guard.requireOwnedAccount(caller, sourceAccountId);
            var beneficiary = guard.requireOwnedBeneficiary(caller, beneficiaryId);

            boolean trusted = beneficiary.trusted();
            RiskDecision decision = riskService.evaluate(trusted, amount, account.dailyLimit());

            int id = transfers.nextId();
            Transfer t = new Transfer(id, account.id(), beneficiary.id(), beneficiary.iban().value(), amount, "CZK");

            routeTransferCreation(account, t, decision);
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
     * @throws NotFoundException when this caller has no such account, whether because none
     *         exists or because it is somebody else's
     */
    public int submitPaymentToIban(int callerCustomerId, int sourceAccountId, String targetIban, double amountCzk, String message) {
        // Validated before the unit of work opens: a rejected amount is caller input, not a
        // reason to start a transaction and roll it back.
        Money amount = Money.czkPayment(amountCzk);

        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            IBAN iban = new IBAN(targetIban);
            var caller = guard.requireCaller(callerCustomerId);
            // The daily limit that decides whether this needs authorization is now read off an
            // account the caller owns, so a victim's limits cannot settle an attacker's payment.
            var account = guard.requireOwnedAccount(caller, sourceAccountId);

            boolean trusted = false;
            RiskDecision decision = riskService.evaluate(trusted, amount, account.dailyLimit());

            int id = transfers.nextId();
            Transfer t = new Transfer(id, account.id(), null, iban.value(), amount, "CZK");

            routeTransferCreation(account, t, decision);
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
    private void routeTransferCreation(Account account, Transfer t, RiskDecision decision) {

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
     * Resolves a transfer the caller named and owns, or refuses the request.
     *
     * Every caller-supplied transfer id goes through here. The ownership check is part of the
     * resolution rather than a step after it, so it cannot be moved below a status guard: a
     * 409 that a non-owner can reach proves the id is real, and transfer ids are small
     * consecutive integers. A transfer belonging to somebody else is refused exactly like one
     * that exists nowhere.
     *
     * Do not make this reusable for the read endpoints of A4 - it belongs to the unit of work
     * its callers opened. The reusable part is {@link OwnershipGuard}.
     */
    private Transfer requireTransfer(Customer caller, int transferId) {
        Transfer t = transfers.byId(transferId)
                .orElseThrow(() -> new NotFoundException("Transfer not found: " + transferId));
        guard.requireOwnedTransfer(caller, t);
        return t;
    }

    /**
     * UC 05 - Authorize Payment.
     *
     * @throws NotFoundException when this caller has no transfer with this id, whether
     *         because none exists or because it debits somebody else's account
     * @throws ConflictException when the transfer is not waiting for authorization
     * @throws InvalidOtpException when the code is wrong and attempts remain
     */
    public void authorizePayment(int callerCustomerId, int transferId, String otp) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            var caller = guard.requireCaller(callerCustomerId);
            var t = requireTransfer(caller, transferId);

            // requireTransfer has just proved the caller owns this account, so a miss here is
            // a dangling row of ours; requireOwnedAccount answers that with the 500 it is.
            var acc = guard.requireOwnedAccount(caller, t.sourceAccountId());

            if (t.status() != TransferStatus.WAITING_AUTH) {
                throw new ConflictException(
                        "Transfer " + transferId + " is not waiting for authorization, it is " + t.status());
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
                boolean attemptsRemain = t.status() == TransferStatus.WAITING_AUTH;
                uow.commit();

                // The attempt is spent whether or not the caller is told so, which is why the
                // refusal is raised after the commit; rollback() is a no-op once the unit of
                // work has completed (JsonUnitOfWork.finish, SqlUnitOfWork.rollback), and both
                // UowScope.close() and the catch below call it on the way out. The last attempt
                // declines the transfer, and that outcome is reported in the response, not as
                // an error.
                if (attemptsRemain) {
                    throw new InvalidOtpException("Wrong one-time password for transfer " + transferId);
                }
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
     *
     * @throws NotFoundException when this caller has no transfer with this id, whether
     *         because none exists or because it debits somebody else's account
     * @throws ConflictException when the transfer has already been sent
     */
    public void cancelPayment(int callerCustomerId, int transferId) {
        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            var caller = guard.requireCaller(callerCustomerId);
            var t = requireTransfer(caller, transferId);
            // Same fact as Transfer.decline's own guard, so it must answer with the same code.
            if (t.status() == TransferStatus.SENT) {
                throw new ConflictException("Cannot cancel an already SENT transfer " + transferId);
            }
            // True again on this path: only the owning customer can reach it. The fraud desk
            // writes its own reason through FraudApplicationService and is not covered here.
            t.decline("Canceled by customer");
            transfers.save(t);
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }
}
