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
import cz.vsb.minibank.domain.exceptions.DailyLimitExceededException;
import cz.vsb.minibank.domain.exceptions.InsufficientFundsException;
import cz.vsb.minibank.domain.exceptions.InvalidOtpException;
import cz.vsb.minibank.domain.exceptions.NotFoundException;
import cz.vsb.minibank.domain.exceptions.SelfTransferNotAllowedException;
import cz.vsb.minibank.domain.exceptions.TransferUnderReviewException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Application service for payment related use cases such as submitting, authorizing and canceling transfers.
 */
public class TransferApplicationService {

    public static final int MAX_OTP_ATTEMPTS = 3;

    /**
     * The zone whose midnight ends a banking day. Daily totals are bounded by start-of-day in
     * this zone converted to an instant, not by a truncated UTC instant: Prague is UTC+1 in
     * winter and UTC+2 in summer, so a UTC boundary would file every late-evening payment
     * under the following day.
     *
     * The constructor forces the injected clock into this zone, so it is the boundary
     * unconditionally and handing the service a Clock.systemUTC() cannot quietly move it.
     */
    public static final ZoneId BANK_ZONE = ZoneId.of("Europe/Prague");

    private final AccountRepository accounts;
    private final OwnershipGuard guard;
    private final TransferRepository transfers;
    private final FraudAlertRepository alerts;
    private final FeePolicy feePolicy;
    private final RiskService riskService;
    private final OtpValidator otpValidator;
    private final PaymentNetworkGateway paymentNetworkGateway;
    private final UnitOfWorkFactory uowFactory;

    /**
     * Stamps every transfer this service creates and bounds every daily total it computes.
     *
     * One clock has to do both. A clock that only bounded the query would make the feature
     * inert under a fixed clock - rows stamped with the real now, windows asked for some other
     * day, every total zero - so a test written against it would pass while proving nothing.
     * That is why {@link Transfer} gained a constructor that takes createdAt.
     *
     * Always in {@link #BANK_ZONE}: the constructor calls withZone, so the injected clock
     * supplies "now" and nothing else.
     */
    private final Clock clock;

    public TransferApplicationService(AccountRepository accounts,
                                      TransferRepository transfers,
                                      FraudAlertRepository alerts,
                                      FeePolicy feePolicy,
                                      RiskService riskService,
                                      OtpValidator otpValidator,
                                      PaymentNetworkGateway paymentNetworkGateway,
                                      UnitOfWorkFactory uowFactory,
                                      OwnershipGuard guard) {
        this(accounts, transfers, alerts, feePolicy, riskService, otpValidator,
                paymentNetworkGateway, uowFactory, guard, Clock.system(BANK_ZONE));
    }

    /**
     * @param clock supplies "now". Its own zone is discarded in favour of {@link #BANK_ZONE}.
     *              The only reason to pass anything but the system clock is a test that has to
     *              place payments on two different days without waiting for one to pass.
     */
    public TransferApplicationService(AccountRepository accounts,
                                      TransferRepository transfers,
                                      FraudAlertRepository alerts,
                                      FeePolicy feePolicy,
                                      RiskService riskService,
                                      OtpValidator otpValidator,
                                      PaymentNetworkGateway paymentNetworkGateway,
                                      UnitOfWorkFactory uowFactory,
                                      OwnershipGuard guard,
                                      Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock").withZone(BANK_ZONE);
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
     * @throws DailyLimitExceededException when this amount would take today's outflow past the
     *         source account's daily ceiling
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

            requireDifferentAccount(account, beneficiary.iban());

            int id = transfers.nextId();
            Transfer t = new Transfer(id, account.id(), beneficiary.id(), beneficiary.iban().value(),
                    amount, "CZK", clock.instant());

            routeTransferCreation(account, t, beneficiary.trusted());
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
     * @throws DailyLimitExceededException when this amount would take today's outflow past the
     *         source account's daily ceiling
     */
    public int submitPaymentToIban(int callerCustomerId, int sourceAccountId, String targetIban, double amountCzk, String message) {
        // Validated before the unit of work opens: a rejected amount is caller input, not a
        // reason to start a transaction and roll it back.
        Money amount = Money.czkPayment(amountCzk);

        var uow = uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            IBAN iban = new IBAN(targetIban);
            var caller = guard.requireCaller(callerCustomerId);
            // The daily limits and the day's running total that decide this payment are read
            // off an account the caller owns, so a victim's limits cannot settle an attacker's
            // payment and a victim's history cannot pay for it either.
            var account = guard.requireOwnedAccount(caller, sourceAccountId);
            requireDifferentAccount(account, iban);

            int id = transfers.nextId();
            Transfer t = new Transfer(id, account.id(), null, iban.value(), amount, "CZK", clock.instant());

            // An arbitrary IBAN is not a saved beneficiary, so it is never a trusted one.
            routeTransferCreation(account, t, false);
            uow.commit();
            return id;
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /**
     * Routes transfer creation on the risk decision: settle now, hold for fraud review, or wait
     * for the customer's authorization.
     *
     * Three branches, and the order matters. An alert takes precedence over an authorization
     * request, because an alerted transfer must not be confirmable by its owner while the alert
     * is open; the customer's confirmation step is what an analyst's APPROVE unlocks.
     *
     * @throws DailyLimitExceededException when the day's outflow plus this amount would pass
     *         the account's ceiling
     */
    private void routeTransferCreation(Account account, Transfer t, boolean beneficiaryTrusted) {

        Money fee = t.feeAmount(feePolicy);
        if (!account.canDebit(t.amount(), fee)) {
            throw new InsufficientFundsException("Insufficient funds");
        }

        // Evaluated here rather than by the callers, below the funds check, so that a payment
        // the account cannot afford is still answered "not enough funds" when it is also over
        // the ceiling - both are true and that is the one the customer can act on. Moving this
        // above canDebit changes the pinned body of
        // HttpErrorContractTest.anAmountAboveTheBalanceIs400InsufficientFunds.
        //
        // The transfer being created has no stored row yet - both backends defer writes to
        // commit - so it is not in the day's total; the risk service adds its amount.
        RiskDecision decision = riskService.evaluate(
                beneficiaryTrusted,
                t.amount(),
                sentOnTheDayOf(account.id(), t.createdAt()),
                account.dailyLimit());

        if (!decision.requireAuthorization() && !decision.createFraudAlert()) {
            transfers.add(t);
            account.registerTransfer(t.id());
            // Both backends read the aggregates at commit, not at registration, so moving the
            // money after these two lines writes exactly what moving it before them would.
            settle(t, account);

        } else if (decision.createFraudAlert()) {
            // Held, not waiting: the alert created below is what an analyst must clear before
            // the customer's code is worth anything. Tested on createFraudAlert alone rather
            // than nested inside the authorization branch, so that an alerted transfer is held
            // whatever the authorization thresholds are later set to. That is also what makes
            // "approve the suspicious transaction" a use case that exists: every alerted
            // transfer is now HELD_FOR_REVIEW at the instant its alert does.
            t.holdForReview(new CardPayment(t.amount(), "****0000"));
            transfers.add(t);
            account.registerTransfer(t.id());
            accounts.save(account);

            FraudAlert a = new FraudAlert(
                    alerts.nextId(),
                    t.id(),
                    Objects.requireNonNullElse(decision.reason(), "Suspicious"),
                    decision.riskScore(),
                    null,
                    null,
                    null
            );
            alerts.add(a);

        } else {
            t.requestAuthorization(new CardPayment(t.amount(), "****0000"));
            transfers.add(t);
            account.registerTransfer(t.id());
            accounts.save(account);
        }
    }

    /**
     * What has already left this account on the banking day that contains {@code when}, fees
     * excluded.
     *
     * The window is derived from the transfer's own creation instant, never from "now", and
     * that is the whole of the fix. A transfer is filed under its createdAt when it settles -
     * that is the only timestamp it carries, because nothing records when one settled:
     * Transfer.send writes no timestamp, and authValidUntil is an expiry deadline that survives
     * non-null on a SENT transfer. So a window taken from "now" at authorization time would
     * check a transfer against a day it will never join. Payments parked just before midnight
     * and authorized just after it would each see a total of zero and each pass, and the
     * ceiling would not bind at all inside that window. Asking about the transfer's own day
     * makes the invariant one the store can prove: for every day D, the SENT transfers created
     * on D sum to at most the account's limit.
     *
     * What that costs, stated plainly: a payment created at 23:57 and authorized at 00:01
     * debits the account on day D+1 but is counted against day D, so a calendar day can see up
     * to two days' budgets leave. That used to be bounded to the five minutes after midnight by
     * authValidUntil. It no longer is: a transfer held for fraud review has no authorization
     * window at all, and neither does one an analyst has released, so the gap between the day a
     * payment is counted against and the day it actually debits is now bounded only by how long
     * the review takes and how long the customer waits before confirming. The per-day invariant
     * is unchanged - for every day D, the SENT transfers created on D still sum to at most the
     * account's limit - it is only the drift that grew. Removing it needs a settlement
     * timestamp, which needs a column in db/init/schema.sql, and that file belongs to A14 and
     * A6; re-stamping createdAt on release is the cheaper alternative and is an owner decision,
     * not one to take inside this change.
     *
     * atStartOfDay on a LocalDate in the zone is DST-correct in both directions; truncating an
     * instant to UTC days is not.
     */
    private Money sentOnTheDayOf(int accountId, Instant when) {
        ZoneId zone = clock.getZone();
        LocalDate day = LocalDate.ofInstant(when, zone);
        return transfers.sentTotalBetween(
                accountId,
                day.atStartOfDay(zone).toInstant(),
                day.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /**
     * Settles a transfer: moves the money, and hands the transfer to the network only when it
     * leaves this bank.
     *
     * One lookup decides both halves, which is why they are one statement apart. An IBAN this
     * bank holds is credited here and is not also offered to the network, because under a real
     * gateway that would be the same money leaving twice - the credit leg would fix the
     * destroyed-money bug and put a double spend in its place. Everything else is unchanged:
     * resolved to nothing, credited to nobody, dispatched exactly as before.
     *
     * Both accounts are saved here rather than by the callers. The destination is the save
     * nobody would remember to write, and the pair has to be registered in one deterministic
     * order across every settle site - see AccountRepository.saveBothInIdOrder.
     */
    private void settle(Transfer t, Account source) {
        Account destination = accounts.inBankByIban(t.targetIbanSnapshot()).orElse(null);
        t.send(source, destination, feePolicy);
        accounts.saveBothInIdOrder(source, destination);
        if (destination == null) {
            paymentNetworkGateway.send(t);
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
     * Refuses a payment that names the source account's own IBAN.
     *
     * Still refused now that a credit leg exists: the account would be debited amount plus fee
     * and credited amount, so the fee would be charged for moving nothing. Refusing it here
     * keeps it a 400 the caller can act on. Reaching {@link Transfer#send} with the source as
     * its own destination means a stored row contradicts this rule, and that is answered as
     * the server fault it is. The comparison is against the account already loaded for this
     * transaction, which is the source the rule is about.
     */
    private void requireDifferentAccount(Account source, IBAN target) {
        if (source.iban().equals(target)) {
            throw new SelfTransferNotAllowedException(
                    "Account " + source.id() + " cannot pay itself: " + target.value());
        }
    }

    /**
     * UC 05 - Authorize Payment.
     *
     * @throws NotFoundException when this caller has no transfer with this id, whether
     *         because none exists or because it debits somebody else's account
     * @throws TransferUnderReviewException when a fraud alert on this transfer is still open.
     *         Nothing changes: no OTP attempt is spent and the transfer stays held. An analyst
     *         has to decide before any code is worth anything, however valid
     * @throws ConflictException when the transfer is not waiting for authorization
     * @throws InsufficientFundsException when the balance no longer covers amount and fee
     * @throws DailyLimitExceededException when other payments have settled since this one was
     *         created and settling it now would pass the account's daily ceiling for the day it
     *         was created on. The transfer is left WAITING_AUTH with its attempts intact: the
     *         customer can cancel it, or let the authorization window expire it, or authorize
     *         it once enough of that day's other payments have been declined
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

            // The gate. An open alert blocks confirmation, and HELD_FOR_REVIEW is how that fact
            // is stored: it is set when the alert is created and left only by an analyst's
            // decision. One source of truth, and the status rather than a re-read of the alert,
            // because the status is what every surface already renders and because it keeps
            // FraudAlertRepository.byTransferId - whose two backends disagree about the
            // identity map - off the debit path entirely.
            //
            // Raised in this class and not in a controller because the console and the demo
            // runner call this method directly, which is why A3's ownership check and A9's
            // ceiling re-check are here too.
            //
            // Above the generic conflict, following A5 and A9: told only that the transfer is
            // "not waiting for authorization", a customer whose payment is under review has no
            // way to see why. Below requireTransfer, because a 409 a non-owner can reach proves
            // the id is real. Above the OTP check, so a refusal that is not about the code
            // spends no attempt, and above the expiry and funds checks, which are meaningless
            // on a transfer that has no window and is not going anywhere.
            if (t.status() == TransferStatus.HELD_FOR_REVIEW) {
                throw new TransferUnderReviewException(
                        "Transfer " + transferId + " is held for fraud review");
            }

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

            // The funds check first, so the two check sites answer a payment that is both
            // unaffordable and over the ceiling the same way creation does. Without it the
            // ceiling would win here and lose there, for no reason a customer could see.
            // Account.debit raises the identical exception from inside settle, so this only
            // moves where it is raised, not what the caller is told.
            if (!acc.canDebit(t.amount(), t.feeAmount(feePolicy))) {
                throw new InsufficientFundsException("Insufficient funds");
            }

            // Asked again here because creation cannot see it: two payments can each be inside
            // the ceiling when they are created and breach it together once both are
            // authorized. Only the ceiling is re-checked - the authorization the soft threshold
            // asks for is the act being performed, so re-applying it would be circular.
            //
            // Against the day the transfer was CREATED, which is the day it will be counted in
            // once it is SENT; see sentOnTheDayOf.
            //
            // Raised before the OTP is looked at, so no attempt is spent on a refusal that is
            // not about the code, and before settle, so nothing has moved. The throw is inside
            // the try, so UowScope.close and the catch both reach uow.rollback() and the
            // transfer is left exactly as it was found: still WAITING_AUTH, so cancelPayment
            // declines it, and the expiry branch above declines it on the next call once the
            // five minute window has run out.
            riskService.requireWithinDailyLimit(
                    t.amount(), sentOnTheDayOf(acc.id(), t.createdAt()), acc.dailyLimit());

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

            // The only other point at which a customer's money moves. Everything above is
            // untouched, so WAITING_AUTH, an expired window and an exhausted OTP still credit
            // nobody: settle is not reached, and neither is the lookup inside it.
            transfers.save(t);
            settle(t, acc);

            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback();
            throw e;
        }
    }

    /**
     * UC 19 - Cancel Payment Order if it has not been sent yet.
     *
     * Deliberately not gated on the review hold. A transfer held for fraud review has no
     * expiry, so if the customer could not withdraw it they would be parked behind a queue with
     * no way forward; the only guard is SENT, exactly as {@link Transfer#decline} has.
     *
     * The alert on a cancelled transfer stays NEW, pointing at a DECLINED transfer. That is a
     * known consequence rather than an oversight - a withdrawn payment that tripped the rules
     * is still evidence, and nothing here should decide unilaterally that it is not - but it
     * does mean an analyst's NEW queue accumulates alerts with nothing left to decide. The
     * queue now carries the transfer's status so they are at least visible as such.
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
