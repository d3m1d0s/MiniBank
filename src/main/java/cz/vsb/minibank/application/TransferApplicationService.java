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
import java.util.Collection;
import java.util.Objects;

/**
 * Application service for payment related use cases such as submitting, authorizing and canceling transfers.
 */
public class TransferApplicationService {

    public static final int MAX_OTP_ATTEMPTS = 3;

    /**
     * The longest payment reference the store can hold, matching transfers.message VARCHAR(140).
     * 140 is the SEPA remittance-information length, which is why the column is that wide.
     */
    public static final int MAX_MESSAGE_LENGTH = 140;

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

    /**
     * No {@link PaymentNetworkGateway} is taken, and its absence is the shape of this class now.
     * It used to hold one for a single call inside {@link #settle}, which is where the phantom
     * dispatch lived; the network is reached from {@link PaymentDispatcher} after a commit, and
     * this service records what is owed rather than paying it.
     */
    public TransferApplicationService(AccountRepository accounts,
                                      TransferRepository transfers,
                                      FraudAlertRepository alerts,
                                      FeePolicy feePolicy,
                                      RiskService riskService,
                                      OtpValidator otpValidator,
                                      UnitOfWorkFactory uowFactory,
                                      OwnershipGuard guard) {
        this(accounts, transfers, alerts, feePolicy, riskService, otpValidator,
                uowFactory, guard, Clock.system(BANK_ZONE));
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
    public PaymentOutcome submitPaymentByBeneficiary(int callerCustomerId, int sourceAccountId, int beneficiaryId, double amountCzk, String message) {
        // Validated before the unit of work opens: rejected input is caller input, not a
        // reason to start a transaction and roll it back.
        Money amount = Money.czkPayment(amountCzk);
        String reference = requireStorableMessage(message);

        try (UowScope scope = new UowScope(uowFactory.begin())) {

            // callerCustomerId comes from the session; the other two are caller input and are
            // resolved only against what that customer owns.
            var caller = guard.requireCaller(callerCustomerId);
            var account = guard.requireOwnedAccount(caller, sourceAccountId);
            var beneficiary = guard.requireOwnedBeneficiary(caller, beneficiaryId);

            requireDifferentAccount(account, beneficiary.iban());

            int id = transfers.nextId();
            Transfer t = new Transfer(id, account.id(), beneficiary.id(), beneficiary.iban().value(),
                    amount, clock.instant());
            t.attachMessage(reference);

            routeTransferCreation(caller, account, t, beneficiary.trusted());

            // Built before the commit, from the aggregates this unit of work owns, so what the
            // caller is told is what this transaction did rather than what the store happened to
            // hold a moment later.
            PaymentOutcome outcome = outcomeOf(t, account);
            scope.uow().commit();
            return outcome;
        }
    }

    /**
     * UC 04 - Submit Payment Order to an arbitrary IBAN.
     * The IBAN is validated by the value object, before any transaction opens - see
     * {@link #requireTargetIban} - and invalid input results in an exception.
     *
     * @return identifier of the created transfer
     * @throws cz.vsb.minibank.domain.exceptions.ValidationException when no target IBAN was
     *         given, or when the message is longer than the store can hold
     * @throws NotFoundException when this caller has no such account, whether because none
     *         exists or because it is somebody else's
     * @throws DailyLimitExceededException when this amount would take today's outflow past the
     *         source account's daily ceiling
     */
    public PaymentOutcome submitPaymentToIban(int callerCustomerId, int sourceAccountId, String targetIban, double amountCzk, String message) {
        // Validated before the unit of work opens: rejected input is caller input, not a
        // reason to start a transaction and roll it back. The destination belongs above this
        // line for that same reason and used to sit below it, which made the rule the comment
        // states untrue for the one field a customer types by hand: a mistyped IBAN is a plain
        // 400, and it was taking the JSON store's global lock, or opening a DriverManager
        // connection with no pool behind it, purely to be torn down again.
        Money amount = Money.czkPayment(amountCzk);
        String reference = requireStorableMessage(message);
        IBAN iban = requireTargetIban(targetIban);

        try (UowScope scope = new UowScope(uowFactory.begin())) {
            var caller = guard.requireCaller(callerCustomerId);
            // The daily limits and the day's running total that decide this payment are read
            // off an account the caller owns, so a victim's limits cannot settle an attacker's
            // payment and a victim's history cannot pay for it either. The payee total the alert
            // rule works from now spans this caller's other accounts as well, and stops there:
            // it is still only their own history, resolved from their own customer row.
            var account = guard.requireOwnedAccount(caller, sourceAccountId);
            requireDifferentAccount(account, iban);

            int id = transfers.nextId();
            Transfer t = new Transfer(id, account.id(), null, iban.value(), amount, clock.instant());
            t.attachMessage(reference);

            // An arbitrary IBAN is not a saved beneficiary, so it is never a trusted one.
            routeTransferCreation(caller, account, t, false);

            PaymentOutcome outcome = outcomeOf(t, account);
            scope.uow().commit();
            return outcome;
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
     * Only the settling branch writes an account, and the two that do not are deliberate rather
     * than forgetful. Nothing on them changes the aggregate: canDebit and the risk evaluation
     * only read it, holdForReview and requestAuthorization change the transfer alone, and the
     * account carries no list of its transfers for a new one to join - see {@link Account}, which
     * says why that field is gone. Both branches used to call accounts.save anyway, and on SQL
     * that is not a no-op: the upsert rewrites identical values and bumps accounts.version, so
     * merely submitting a payment took a serialization point on the account row. A submission
     * that only parks a transfer at WAITING_AUTH could then be answered 409
     * CONCURRENT_MODIFICATION because it raced an authorization of some older transfer from the
     * same account, over an operation that moved no money. Anything a future branch here does
     * change on the account has to save it; nothing on these two does.
     *
     * Takes the caller as well as the account because the two totals it computes have two
     * scopes: the ceiling and the soft tier are measured over this account alone, being columns
     * on it, and the payee total over every account this customer holds. {@link RiskService}
     * states that seam in full. The caller is the aggregate {@link OwnershipGuard} already
     * resolved and the account is one it has already proved belongs to that caller, so widening
     * the total reaches nothing new.
     *
     * @throws DailyLimitExceededException when the day's outflow plus this amount would pass
     *         the account's ceiling
     */
    private void routeTransferCreation(Customer caller, Account account, Transfer t,
                                       boolean beneficiaryTrusted) {

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
                sentToPayeeOnTheDayOf(caller.accountIds(), t.targetIbanSnapshot(), t.createdAt()),
                account.dailyLimit(),
                account.softDailyThreshold());

        if (!decision.requireAuthorization() && !decision.createFraudAlert()) {
            transfers.add(t);
            // Both backends read the aggregates at commit, not at registration, so moving the
            // money after this line writes exactly what moving it before it would.
            //
            // createdAt is the settlement instant on this path, and it is the same reading of
            // the clock the day window above was taken from - which is the rule settle()
            // documents. A payment that settles at creation was ordered and paid in one step.
            settle(t, account, t.createdAt());

        } else if (decision.createFraudAlert()) {
            // Held, not waiting: the alert created below is what an analyst must clear before
            // the customer's code is worth anything. Tested on createFraudAlert alone rather
            // than nested inside the authorization branch, so that an alerted transfer is held
            // whatever the authorization thresholds are later set to. That is also what makes
            // "approve the suspicious transaction" a use case that exists: every alerted
            // transfer is now HELD_FOR_REVIEW at the instant its alert does.
            t.holdForReview(new CardPayment(t.amount(), "****0000"));
            transfers.add(t);

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
        }
    }

    /**
     * Turns the caller's destination into a value object, or refuses the request.
     *
     * An omitted destination and a mistyped one are not the same complaint, and the difference
     * was worth a guard because the value object cannot make it. {@link IBAN}'s constructor
     * opens with a bare requireNonNull, so a request that simply left the field out arrived at
     * the HTTP edge as a NullPointerException; no handler in RestExceptionHandler claims that
     * type, so it fell to the catch-all and was answered 500 with an error-level stack trace.
     * That tells the customer the bank is broken and tells the log the same, over a request
     * that was merely incomplete.
     *
     * Refused as a missing value rather than as a malformed IBAN, following the login screen's
     * refusal of an absent password. INVALID_IBAN reads "The IBAN you entered is not valid",
     * which is a sentence about something the customer never entered; VALIDATION_ERROR is the
     * catalogue entry that already says "invalid or missing values", and it needs nothing new
     * added to the contract. Anything actually present, blank included, keeps INVALID_IBAN,
     * because then there is a value to look at and correct.
     *
     * @throws cz.vsb.minibank.domain.exceptions.ValidationException when no destination was given
     * @throws cz.vsb.minibank.domain.exceptions.InvalidIbanException when the destination given
     *         is not a well-formed Czech IBAN
     */
    private static IBAN requireTargetIban(String targetIban) {
        if (targetIban == null) {
            throw new cz.vsb.minibank.domain.exceptions.ValidationException("Target IBAN is required");
        }
        return new IBAN(targetIban);
    }

    /**
     * Trims the customer's own reference and refuses one the store cannot hold.
     *
     * transfers.message is VARCHAR(140), the SEPA remittance-information length. Refused here
     * rather than truncated: silently shortening a payment reference changes what a beneficiary
     * is told the money is for, and a variable symbol cut in half is worse than a rejected form.
     *
     * In this class and not in a controller for the reason every other guard here is: the
     * console and the demo runner call these methods directly, and a rule enforced only at the
     * HTTP edge is a rule two of the three callers do not have.
     *
     * Blank becomes null, so an empty input box and an omitted field store the same thing -
     * nothing - rather than an empty string that renders as a reference the customer never gave.
     *
     * @throws cz.vsb.minibank.domain.exceptions.ValidationException when the message is longer
     *         than 140 characters after trimming
     */
    private static String requireStorableMessage(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            throw new cz.vsb.minibank.domain.exceptions.ValidationException(
                    "Payment message must not exceed " + MAX_MESSAGE_LENGTH
                            + " characters, this one is " + trimmed.length());
        }
        return trimmed;
    }

    /**
     * What has already settled out of this account on the banking day that contains
     * {@code when}, fees excluded.
     *
     * Keyed on settled_at, which is the day the money actually left. Before that column existed
     * a transfer carried only its createdAt, so a settled payment was filed under the day it was
     * ordered - and the window had to be taken from the transfer's own creation instant or it
     * would have asked about a day the transfer would never join. That bought a per-day
     * invariant at the price of drift: a payment created at 23:57 and authorized at 00:01
     * debited the account on day D+1 while counting against day D, so one calendar day could see
     * two days' budgets leave. The review hold made that drift unbounded by removing the authorization window
     * from held transfers - a review can take as long as it takes, and so can the customer
     * afterwards.
     *
     * With settled_at the drift is gone and the invariant is the stronger one: for every day D,
     * the transfers that settled on D sum to at most the account's limit. Both check sites pass
     * the instant that will be, or already is, the settlement instant - creation passes
     * t.createdAt(), which is the same clock reading it settles with on the immediate path, and
     * authorization passes the now it is about to stamp. One instant decides the window and the
     * stamp, for the reason the daily total gives about createdAt: two readings would let a payment be checked
     * against one day and filed under the next.
     *
     * A row with no settled_at - anything written before this column - falls back to its
     * created_at in both backends, so no historical total moves. See
     * SqlTransferRepository.sumSentWithConnection and JsonTransferRepository.sentTotalBetween.
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
     * The same day window, narrowed to one payee and widened to every account the customer
     * holds. Feeds the alert rule and nothing else.
     *
     * The asymmetry with {@link #sentOnTheDayOf} is deliberate, and {@link RiskService#evaluate}
     * states it where a reader meets both totals at once: the ceiling and the soft tier are
     * columns on one account and have to be measured over that account's rows, while the payee
     * total is a fact about a customer and was defeated outright by anyone splitting a payment
     * across two accounts of their own.
     *
     * The ids are the caller's own, off the {@link Customer} that {@link OwnershipGuard} already
     * resolved. Reading them from there rather than querying accounts by customer is what keeps
     * this to no extra lookup and to exactly the scope ownership is decided by everywhere else
     * in this class.
     */
    private Money sentToPayeeOnTheDayOf(Collection<Integer> accountIds, String targetIban,
                                        Instant when) {
        ZoneId zone = clock.getZone();
        LocalDate day = LocalDate.ofInstant(when, zone);
        return transfers.sentTotalToIbanBetween(
                accountIds,
                targetIban,
                day.atStartOfDay(zone).toInstant(),
                day.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /**
     * Settles a transfer: moves the money, and leaves a payment that is going outside this bank
     * owing the network a dispatch.
     *
     * One lookup decides both halves, which is why they are one statement apart. An IBAN this
     * bank holds is credited here and is not also offered to the network, because under a real
     * gateway that would be the same money leaving twice - the credit leg would fix the
     * destroyed-money bug and put a double spend in its place. {@link Transfer#send} makes both
     * decisions off that one answer: it credits a destination it is given, and records the
     * dispatch as PENDING when it is given none.
     *
     * Nothing here calls a gateway, and that absence is the point of this method now. It used to
     * hand the transfer to the network on the spot, from inside the caller's still-open unit of
     * work. Every row write is deferred to commit, so a commit that then failed rolled the debit
     * and the SENT status back over a payment that had already been dispatched, and answered the
     * customer that nothing had been charged and to send it again. The obligation is now written
     * with the debit, in one transaction, and {@link PaymentDispatcher} discharges it once that
     * transaction has committed.
     *
     * Both accounts are saved here rather than by the callers. The destination is the save
     * nobody would remember to write, and the pair has to be registered in one deterministic
     * order across every settle site - see AccountRepository.saveBothInIdOrder.
     *
     * @param settledAt the instant the money moves, which must be the one that bounded the
     *                  caller's daily-total window - see {@link #sentOnTheDayOf}
     */
    private void settle(Transfer t, Account source, Instant settledAt) {
        Account destination = accounts.inBankByIban(t.targetIbanSnapshot()).orElse(null);
        t.send(source, destination, feePolicy, settledAt);
        accounts.saveBothInIdOrder(source, destination);
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
     * Do not make this reusable for the read endpoints - it belongs to the unit of work
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
     * @throws DailyLimitExceededException when settling this payment now would pass the
     *         account's daily ceiling for today - the day it is about to settle on, not the day
     *         it was created on. The transfer is left WAITING_AUTH with its attempts intact: the
     *         customer can cancel it, or let the authorization window expire it, or come back
     *         tomorrow, which is a way out the old rule did not have
     * @throws InvalidOtpException when the code is wrong and attempts remain
     * @throws cz.vsb.minibank.domain.exceptions.OptimisticLockException when another transaction
     *         changed the source or destination account between this one reading its balance and
     *         writing the new one. Nothing is charged and nothing is dispatched: the JDBC
     *         transaction is rolled back with the debit still inside it, and the payment reaches
     *         the network only from {@link PaymentDispatcher}, which runs after a commit that
     *         succeeded. That is what makes the 409 body's "nothing was charged, send it again"
     *         safe to act on - the resubmission can no longer be the second dispatch of a payment
     *         the first attempt had already handed over
     */
    public PaymentOutcome authorizePayment(int callerCustomerId, int transferId, String otp) {
        // Read once. This instant bounds the day this payment is checked against and is the
        // instant it is stamped as settling at, and they have to be the same reading: two calls
        // either side of midnight would check a payment against one day and file it under the
        // next, which is the hole settled_at exists to close.
        Instant now = clock.instant();

        try (UowScope scope = new UowScope(uowFactory.begin())) {
            var caller = guard.requireCaller(callerCustomerId);
            var t = requireTransfer(caller, transferId);

            // requireTransfer has just proved the caller owns this account, so a miss here is
            // a dangling row of ours; requireOwnedAccount answers that with the 500 it is.
            var acc = guard.requireOwnedAccount(caller, t.sourceAccountId());

            // The gate. An open alert blocks confirmation, and HELD_FOR_REVIEW is how that fact
            // is stored: it is set when the alert is created and left only by an analyst's
            // decision. One source of truth, and the status rather than a re-read of the alert,
            // because the status is what every surface already renders; the alert row is
            // consulted only further down, where the risk re-check has to know whether one
            // already exists.
            //
            // Raised in this class and not in a controller because the console and the demo
            // runner call this method directly, which is why the ownership check and the
            // ceiling re-check are here too.
            //
            // Above the generic conflict, following the self-payment and daily-limit refusals: told only that the transfer is
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
                PaymentOutcome outcome = outcomeOf(t, acc);
                scope.uow().commit();
                return outcome;
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
            // Against the day this payment is about to settle on, which is now - not the day it
            // was ordered on, which may be weeks ago if an analyst held it. That is the change
            // settled_at makes: the two instants used to be forced to be the same one because a
            // transfer carried no record of when it settled. See sentOnTheDayOf.
            //
            // Raised before the OTP is looked at, so no attempt is spent on a refusal that is
            // not about the code, and before settle, so nothing has moved. The throw is inside
            // the try, so UowScope.close rolls the unit of work back on the way out and the
            // transfer is left exactly as it was found: still WAITING_AUTH, so cancelPayment
            // declines it, and the expiry branch above declines it on the next call once the
            // five minute window has run out.
            riskService.requireWithinDailyLimit(
                    t.amount(), sentOnTheDayOf(acc.id(), now), acc.dailyLimit());

            // And the alert, asked again for the same reason and with the same shape. A rule
            // that totals what has gone to one payee cannot see at creation what has not
            // settled yet: two payments of 6 500 to one new payee are each under the threshold
            // when they are made, and the second crosses it only once the first has gone. That
            // ordering is the one an attacker controls, so a rule asked only at creation closes
            // the convenient half of that and not the other one. The two halves need not leave
            // the same account either, which is why this site widened with the other one.
            //
            // Asked once per transfer. An alert that already exists has been seen by an analyst
            // or is waiting to be, and raising a second one would make an approved payment
            // permanently unconfirmable: released to WAITING_AUTH, held again on the next
            // attempt, released again.
            //
            // Above the OTP check, like the ceiling, so a refusal that is not about the code
            // spends no attempt. The hold is committed and only then reported: a rolled-back
            // hold would leave the alert unraised and the payment still confirmable, which is
            // the outcome this exists to prevent.
            if (alerts.byTransferId(transferId).isEmpty()) {
                // Read now rather than trusted at creation: a beneficiary the customer has since
                // marked untrusted is untrusted. An IBAN payment has no beneficiary row and is
                // never trusted, exactly as submitPaymentToIban decides at creation.
                boolean trustedNow = t.beneficiaryId() != null
                        && guard.requireOwnedBeneficiary(caller, t.beneficiaryId()).trusted();

                RiskDecision atAuthorization = riskService.evaluate(
                        trustedNow,
                        t.amount(),
                        sentOnTheDayOf(acc.id(), now),
                        sentToPayeeOnTheDayOf(caller.accountIds(), t.targetIbanSnapshot(), now),
                        acc.dailyLimit(),
                        acc.softDailyThreshold());

                if (atAuthorization.createFraudAlert()) {
                    t.holdForReviewOnAuthorization();
                    transfers.save(t);
                    alerts.add(new FraudAlert(
                            alerts.nextId(),
                            t.id(),
                            Objects.requireNonNullElse(atAuthorization.reason(), "Suspicious"),
                            atAuthorization.riskScore(),
                            null,
                            null,
                            null));
                    scope.uow().commit();

                    throw new TransferUnderReviewException(
                            "Transfer " + transferId + " is held for fraud review");
                }
            }

            boolean valid = otpValidator.isValid(transferId, otp);
            if (!valid) {
                t.registerFailedOtpAttempt(MAX_OTP_ATTEMPTS);
                transfers.save(t);
                boolean attemptsRemain = t.status() == TransferStatus.WAITING_AUTH;
                PaymentOutcome outcome = outcomeOf(t, acc);
                scope.uow().commit();

                // The attempt is spent whether or not the caller is told so, which is why the
                // refusal is raised after the commit; rollback() is a no-op once the unit of
                // work has completed (JsonUnitOfWork.finish, SqlUnitOfWork.rollback), and
                // UowScope.close() calls it on the way out regardless. The last attempt
                // declines the transfer, and that outcome is reported in the response, not as
                // an error.
                if (attemptsRemain) {
                    throw new InvalidOtpException("Wrong one-time password for transfer " + transferId);
                }
                return outcome;
            }

            // The only other point at which a customer's money moves. Everything above is
            // untouched, so WAITING_AUTH, an expired window and an exhausted OTP still credit
            // nobody: settle is not reached, and neither is the lookup inside it.
            transfers.save(t);
            settle(t, acc, now);

            PaymentOutcome outcome = outcomeOf(t, acc);
            scope.uow().commit();
            return outcome;
        }
    }

    /**
     * The facts about a transfer and the account behind it, as this unit of work leaves them.
     *
     * Called from inside the scope on purpose, and from every path that returns rather than
     * throws. Both objects come from the identity map, so they are this transaction's own view
     * and not a second read of the store - which is the point: a caller told what it did must be
     * told what *it* did.
     */
    private PaymentOutcome outcomeOf(Transfer t, Account account) {
        return new PaymentOutcome(
                t.id(),
                t.status(),
                t.amount(),
                t.feeFor(feePolicy),
                account.balance(),
                t.declineReason());
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
     * is still evidence, and nothing here should decide unilaterally that it is not.
     *
     * What it used to mean was that an analyst's NEW queue filled with alerts that had nothing
     * left to decide. It no longer does: the queue carries the transfer's status, and
     * {@code GET /api/fraud/alerts} takes an {@code excludeTransferStatus} filter that both
     * desks apply to withdrawn payments by default, with the control to show them again on
     * screen. The alert is hidden from a view, not resolved - its state is still the analyst's
     * verdict, and nothing outside {@code FraudApplicationService} writes it.
     *
     * Covered by the transfers version column, and worth naming because accounts.version cannot
     * see this path: cancelling writes only the transfers row. Two tabs, one WAITING_AUTH
     * transfer: whichever of a cancel and an authorization commits second is built on a stale
     * read, and the version-guarded upsert in SqlTransferRepository refuses it with
     * TransferChangedException instead of letting DECLINED land over SENT or a cancelled
     * payment charge its owner. FraudApplicationService.decline sits behind the same guard. On
     * the JSON backend the store lock serializes whole transactions, so the race cannot form
     * there.
     *
     * @throws NotFoundException when this caller has no transfer with this id, whether
     *         because none exists or because it debits somebody else's account
     * @throws ConflictException when the transfer has already been sent
     */
    public PaymentOutcome cancelPayment(int callerCustomerId, int transferId) {
        try (UowScope scope = new UowScope(uowFactory.begin())) {
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

            // The one path that has to load the account rather than already holding it: nothing
            // above this line touches it, because cancelling moves no money. One read inside the
            // transaction, replacing the two the controller used to make outside it.
            var account = guard.requireOwnedAccount(caller, t.sourceAccountId());

            PaymentOutcome outcome = outcomeOf(t, account);
            scope.uow().commit();
            return outcome;
        }
    }
}
