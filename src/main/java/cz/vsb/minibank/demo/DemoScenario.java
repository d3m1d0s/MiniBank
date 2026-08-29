package cz.vsb.minibank.demo;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.infrastructure.uow.UowScope;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The single definition of the demo dataset, shared by every entry point and
 * both persistence backends.
 * <p>
 * Balances and fees are produced by executing the domain rather than being
 * written down, so they stay consistent with whatever {@link FeePolicy} is in
 * force. The primary account IBAN is the only demo identifier that exists as a
 * constant; every technical id comes from the repositories.
 */
public final class DemoScenario {

    /**
     * Natural key of the demo dataset. Because accounts.iban is unique in the
     * SQL schema, presence of this IBAN is both the seeded-already check and
     * the constraint that would reject a second seed.
     */
    public static final IBAN PRIMARY_IBAN = new IBAN("CZ6508000000192000145399");
    public static final IBAN SECONDARY_IBAN = new IBAN("CZ4308000000192000145407");
    public static final IBAN TRUSTED_BENEFICIARY_IBAN = new IBAN("CZ2108000000192000145415");
    public static final IBAN UNTRUSTED_BENEFICIARY_IBAN = new IBAN("CZ9608000000192000145423");

    private static final String CUSTOMER_NAME = "Alice Demo";
    private static final String CUSTOMER_EMAIL = "alice@example.com";

    /**
     * Deliberately above {@link #CUSTOMER_DAILY_LIMIT}. The ceiling caps what leaves the customer
     * in a day, so an opening balance below it would be the thing that refuses a large payment,
     * for want of funds, and the ceiling would never get to refuse anything: the topmost of the
     * three outcomes would be unreachable on this dataset and the rule invisible to anyone trying
     * it. At 50 000, and with the seeded fortnight below taking 4 661,50 of it, a single payment
     * can still cross 40 000 and be told so, and what is left covers several passes along the
     * soft tier underneath.
     */
    private static final Money PRIMARY_OPENING_BALANCE = Money.czk(50_000);

    /**
     * The hard ceiling on one day's outflow, above RuleBasedRiskService's 15 000 soft threshold
     * so the demo can cross that one - which only asks for an authorization the script then
     * supplies - without being refused outright. The peak cumulative attempt across DemoRunner
     * is 23 200, so this clears it.
     *
     * One allowance for the person rather than one per account: payments out of either account
     * spend the same 40 000. Split per account it was no ceiling at all, because the customer
     * could pay their own second account and start again on its allowance.
     *
     * No soft-tier override goes with it: the customer rides the bank-wide 15 000, which is the
     * threshold DemoRunner's script is written against.
     */
    private static final Money CUSTOMER_DAILY_LIMIT = Money.czk(40_000);
    private static final Money SECONDARY_OPENING_BALANCE = Money.czk(5_000);

    /**
     * Which of the payees a past payment went to. The beneficiaries themselves are built inside
     * the unit of work and have no ids until then, so the table below names them and
     * {@link #create} resolves the name.
     */
    private enum Payee { TRUSTED, RISKY, SAVINGS, CURRENT }

    /**
     * Which of the customer's two accounts paid.
     *
     * The savings account used to pay for nothing. Every seeded payment left the current account,
     * so the customer's own history had one source on every line, and the heading promising
     * payments from all of their accounts was promising something the data could not show.
     */
    private enum Payer { CURRENT, SAVINGS }

    /**
     * One settled payment: how far back it was made, what it moved, from which account and to whom.
     *
     * The offsets are relative rather than absolute so that the dataset keeps its shape whenever
     * it is seeded, instead of ageing into a fixture about a fortnight that has long passed.
     *
     * The hour is subtracted as well as the days, so the history does not print the same clock
     * time on every line. Shifting back by less than a day can only move a payment later within
     * its own day, never onto the day before, so no two rows here can land on one date.
     *
     * {@code message} is the payer's own reference, null on a payment they wrote none for, and
     * the null is as much of the fixture as the text is. Every screen that prints this field
     * prints it only where there is something to print, so a dataset where all eleven rows carry
     * one exercises exactly one half of that rule and leaves the other half - the row that draws
     * nothing extra - untested by eye on the only data anybody opens the showcase with. It is
     * also what the field means: a box on the payment form that nobody is obliged to fill in.
     * Seven of the eleven have one.
     */
    private record PastPayment(int daysAgo, int hoursAgo, Money amount, Payer from, Payee to,
                               String message) {

        Instant at(Instant seededAt) {
            return seededAt.minus(Duration.ofDays(daysAgo)).minus(Duration.ofHours(hoursAgo));
        }
    }

    /**
     * The fortnight of settled payments the demo opens with, oldest first.
     *
     * Eleven of them rather than two, and dated across days rather than all stamped with the
     * moment the database was created. Everything that reads this data reads it as a history: the
     * customer's own list, the analyst's view of a customer, the date filters over the queue, the
     * paging control that only appears past its first page. Two rows sharing one timestamp answer
     * none of those, and the screens were being judged against a history built by hand for the
     * occasion rather than against the one the product ships.
     *
     * Four of the eleven stay inside the bank and two of those are paid by the savings account, so
     * the credit leg is taken in both directions, the column that says whether the money left the
     * bank has both answers in it, and the customer's history has two sources on it rather than
     * one. The amounts are small and no two of them share a day, so nothing here comes near the
     * customer's daily ceiling, and together they leave the current account comfortably above the
     * payment that is waiting for a code, which is the one a reader is meant to be able to
     * confirm.
     *
     * Which four are silent is chosen rather than left to fall out. The two payments to the
     * untrusted payee are one with a reference and one without, because that pair is what an
     * analyst reads: the same payee, the same customer, and the only difference is whether the
     * payer said what the money was for. Both directions of the leg between the customer's own
     * two accounts carry one, so the internal pair reads as a round trip and not as two
     * unrelated amounts. The rest are ordinary and half of them say nothing, which is how a real
     * fortnight looks.
     */
    private static final List<PastPayment> HISTORY = List.of(
            new PastPayment(13, 2, Money.czk(1_500), Payer.CURRENT, Payee.RISKY, null),
            new PastPayment(12, 7, Money.czk(650), Payer.CURRENT, Payee.TRUSTED, "Share of the rent"),
            new PastPayment(10, 5, Money.czk(150.50), Payer.CURRENT, Payee.TRUSTED, "Concert ticket"),
            new PastPayment(8, 1, Money.czk(1_400), Payer.CURRENT, Payee.SAVINGS, "Putting aside for the deposit"),
            new PastPayment(7, 9, Money.czk(890), Payer.CURRENT, Payee.TRUSTED, null),
            new PastPayment(6, 8, Money.czk(420), Payer.SAVINGS, Payee.TRUSTED, "Dentist, second visit"),
            new PastPayment(5, 3, Money.czk(1_200), Payer.CURRENT, Payee.TRUSTED, null),
            new PastPayment(4, 10, Money.czk(2_500), Payer.SAVINGS, Payee.CURRENT, "Back to the current account for the rent"),
            new PastPayment(3, 6, Money.czk(300), Payer.CURRENT, Payee.SAVINGS, null),
            new PastPayment(2, 11, Money.czk(980), Payer.CURRENT, Payee.RISKY, "Deposit for the workshop"),
            new PastPayment(1, 4, Money.czk(50), Payer.CURRENT, Payee.TRUSTED, "Coffee"));

    /** Above the fraud-alert threshold, so it waits for authorization and raises an alert. */
    private static final Money FLAGGED_AMOUNT = Money.czk(12_000);

    /** Above the same threshold, and the payment behind the alert that has already been decided. */
    private static final Money WITHDRAWN_AMOUNT = Money.czk(11_500);

    /**
     * Who decided that alert, and it is the login the fraud desk ships with rather than a name
     * invented for the fixture. FraudAlert leaves this null where the deciding surface has no
     * users to name; the desk has one, and every screen that prints who closed an alert needs a
     * closed alert with a name on it before it can be read at all.
     */
    private static final String DEMO_ANALYST = "fraud";
    /**
     * Above the bank-wide soft tier of 15 000 once the two settled payments above are counted, so
     * the bank asks for a code. To the trusted payee on purpose: the untrusted one already carries
     * the flagged payment, and a second large one to the same payee would be a row the alert rules
     * say should have been flagged and was not.
     */
    private static final Money AWAITING_AMOUNT = Money.czk(16_000);
    /**
     * The window on the seeded payment, and it is not the product's five minutes.
     *
     * A fixture is written once and read whenever somebody opens the showcase. At five minutes the
     * only screen a customer has for entering a code had nothing it could be asked about: both
     * payments on the stand were ten days past their deadline, so the screen was correct and
     * useless, and the one gate that judged it had to build the state by hand in the page. Thirty
     * days is long enough that the answer does not depend on when the data was seeded.
     */
    private static final Duration AWAITING_WINDOW = Duration.ofDays(30);

    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final FraudAlertRepository alerts;
    private final UnitOfWorkFactory uowFactory;
    private final FeePolicy feePolicy;

    public DemoScenario(CustomerRepository customers,
                        AccountRepository accounts,
                        TransferRepository transfers,
                        FraudAlertRepository alerts,
                        UnitOfWorkFactory uowFactory,
                        FeePolicy feePolicy) {
        this.customers = Objects.requireNonNull(customers);
        this.accounts = Objects.requireNonNull(accounts);
        this.transfers = Objects.requireNonNull(transfers);
        this.alerts = Objects.requireNonNull(alerts);
        this.uowFactory = Objects.requireNonNull(uowFactory);
        this.feePolicy = Objects.requireNonNull(feePolicy);
    }

    /**
     * Creates the demo dataset if it is absent and returns the demo customer id.
     * Repeated calls are no-ops that return the same id.
     */
    public int seed() {
        try (UowScope scope = new UowScope(uowFactory.begin())) {
            UnitOfWork uow = scope.uow();
            Optional<Account> alreadySeeded = accounts.byIban(PRIMARY_IBAN);
            if (alreadySeeded.isPresent()) {
                int ownerId = customers.byAccountId(alreadySeeded.get().id())
                        .orElseThrow(() -> new IllegalStateException(
                                "Demo account " + PRIMARY_IBAN.value() + " exists but belongs to no customer"))
                        .id();
                uow.rollback();
                return ownerId;
            }

            int customerId = create(uow);
            uow.commit();
            return customerId;
        }
    }

    private int create(UnitOfWork uow) {
        int customerId = customers.nextId();
        Customer customer = new Customer(
                customerId,
                CUSTOMER_NAME,
                CUSTOMER_EMAIL,
                new Address("Hlavni 1", "Ostrava"),
                CUSTOMER_DAILY_LIMIT);
        customers.save(customer);

        Account primary = openAccount(customer, PRIMARY_IBAN, PRIMARY_OPENING_BALANCE);
        Account savings = openAccount(customer, SECONDARY_IBAN, SECONDARY_OPENING_BALANCE);
        // Not a redundant repeat of the save above. In SQL mode this is what writes
        // accounts.customer_id: SqlAccountRepository leaves the column NULL and only
        // SqlCustomerRepository.upsertCustomer assigns it, from Customer.accountIds(), which
        // is empty on the first save. That column is the sole record of ownership and is what
        // OwnershipGuard reads, so dropping this line makes every money path 404 in SQL.
        customers.save(customer);

        Beneficiary trusted = addBeneficiary(customerId, "Bob Trusted", TRUSTED_BENEFICIARY_IBAN, true);
        Beneficiary risky = addBeneficiary(customerId, "Mallory Risky", UNTRUSTED_BENEFICIARY_IBAN, false);
        // The customer's own second account, saved as a payee like any other. Without it the
        // dataset had no payment that stays inside the bank at all: both beneficiaries above are
        // foreign IBANs, so every seeded and every hand-made payment left, the credit leg was
        // never taken, and any screen that says where the money went had one answer for every row
        // it will ever draw. A distinction the data cannot exercise is a distinction nobody can
        // check, and this is the showcase for a fraud desk.
        Beneficiary toSavings = addBeneficiary(customerId, "Own savings", SECONDARY_IBAN, true);
        // The same account the other way round. The customer moves money between their own two
        // accounts in both directions, and without this payee the savings account had somewhere to
        // be paid and nowhere to pay: the credit leg only ever ran towards it.
        Beneficiary toCurrent = addBeneficiary(customerId, "Own current account", PRIMARY_IBAN, true);

        // One instant for the whole dataset. Every date below is measured back from it, so the
        // seed reads the clock once and the rows keep their spacing however long it takes to run.
        Instant seededAt = Instant.now();

        for (PastPayment past : HISTORY) {
            Account source = switch (past.from()) {
                case CURRENT -> primary;
                case SAVINGS -> savings;
            };
            Beneficiary target = switch (past.to()) {
                case TRUSTED -> trusted;
                case RISKY -> risky;
                case SAVINGS -> toSavings;
                case CURRENT -> toCurrent;
            };
            settleTransfer(source, target, past.amount(), past.at(seededAt), past.message());
        }

        // The three payments that are not history. These carry the states a screen can act on, so
        // they are dated now rather than back in the fortnight: an alert raised a week ago on a
        // payment still sitting in the queue would say the desk had been unattended for a week.
        flagTransfer(primary, risky, seededAt);
        withdrawnTransfer(primary, risky, seededAt);
        awaitTransfer(primary, trusted, seededAt);

        return customerId;
    }

    private Account openAccount(Customer owner, IBAN iban, Money balance) {
        Account account = new Account(accounts.nextId(), iban, balance);
        accounts.save(account);
        owner.addAccountId(account.id());
        return account;
    }

    private Beneficiary addBeneficiary(int customerId, String name, IBAN iban, boolean trusted) {
        Beneficiary beneficiary = new Beneficiary(customers.nextBeneficiaryId(), name, iban, trusted);
        customers.saveBeneficiary(customerId, beneficiary);
        return beneficiary;
    }

    /**
     * A completed payment, so the demo opens with a non-empty history and a
     * balance that reflects the current fee policy.
     */
    private void settleTransfer(Account source, Beneficiary target, Money amount, Instant at,
                                String message) {
        Transfer transfer = newTransfer(source, target, amount, at);
        // Attached before the payment settles, which is the order it happens in for real: the
        // reference is typed on the form and travels with the payment. Null is passed straight
        // through rather than guarded, because attaching nothing and never attaching leave the
        // same record, and a guard here would suggest they differ. The 140-character rule is
        // TransferApplicationService's and does not run on this path, so the sentences in
        // HISTORY are short by hand.
        transfer.attachMessage(message);
        // The seed asks the same question the services ask instead of hard-coding null. The two
        // accounts above are created in this very unit of work and have no rows yet, which is
        // why inBankByIban consults the identity map before the store. One payee is now the
        // customer's own second account, so this answers with a destination for that one and
        // takes the credit leg, and answers with nothing for the foreign IBANs.
        Account destination = accounts.inBankByIban(target.iban().value()).orElse(null);
        // Its own creation instant is the settlement instant: the seed orders and pays in one
        // step, so there is exactly one moment here and no second reading to disagree with.
        // This keeps the seed off the system clock, which it reads once in create and nowhere else.
        transfer.send(source, destination, feePolicy, at);
        transfers.add(transfer);
        accounts.saveBothInIdOrder(source, destination);
    }

    /**
     * A payment large enough to trip the fraud rules, so the analyst queue and the customer's
     * pending list are both non-empty.
     *
     * Held, not waiting: the seed has to produce the shape the service produces. Seeding it as
     * WAITING_AUTH would ship the exact bug the review gate closes - an alert sitting NEW in
     * the queue on a transfer the customer can confirm at will.
     */
    private void flagTransfer(Account source, Beneficiary target, Instant at) {
        Transfer transfer = newTransfer(source, target, FLAGGED_AMOUNT, at);
        transfer.holdForReview(new CardPayment(transfer.amount(), "****0000"));
        transfers.add(transfer);
        accounts.save(source);

        FraudAlert alert = new FraudAlert(
                alerts.nextId(),
                transfer.id(),
                "New beneficiary + high amount",
                80,
                null,
                null);
        alerts.add(alert);

        // Triage, written before anybody took the alert, which is the state it is seeded in.
        note(alert, "Payee was added this month and this is the largest amount the account has "
                + "ever sent to it. Holding until the card scheme comes back on the merchant.");
        note(alert, "Called the number on file, no answer. Trying again this afternoon.");
    }

    /**
     * A payment that was flagged, withdrawn by the customer while it sat in the queue, and then
     * closed by the analyst with a note.
     *
     * The second alert in the dataset and the only decided one. With a single alert in it the
     * queue had one state, one risk score and no verdict anywhere: the state filter, the sort by
     * state, the colour that separates an open alert from a closed one, the line naming who
     * decided it and the box holding what they wrote could each only be read against an empty
     * answer, which is no reading at all.
     *
     * Closed on a withdrawn payment rather than on a settled one because that outcome is reachable
     * with the transitions the domain already has. Approving an alert does not release the payment
     * behind it, and a seed that walked a held transfer all the way back to SENT would be
     * asserting a path through the review that belongs to the service, not to a fixture.
     */
    private void withdrawnTransfer(Account source, Beneficiary target, Instant at) {
        // Asked for before it was withdrawn, and the gap is the point rather than realism. The
        // history table draws the moment a payment was made over the moment it ended, and with
        // one instant for both the two lines printed the same string: a reader could not tell
        // that the second line is a different fact, and a mapper reading the wrong one of the two
        // would have looked correct on the only data anybody opens the showcase with. This is the
        // rule BankBoundaryOnTheWireTest already applies to the settlement instant, which it
        // pulls three days off the creation for the same reason.
        //
        // It also reads like what it is: a payment sits in the review queue for a while, and the
        // customer gives up on it.
        Transfer transfer = newTransfer(source, target, WITHDRAWN_AMOUNT,
                at.minus(Duration.ofHours(20)));
        transfer.holdForReview(new CardPayment(transfer.amount(), "****0000"));
        // Refused at the instant the fixture is seeded, which is the instant the alert beside it
        // is resolved at. The seed reads the clock once for the whole dataset, so the two halves
        // of this case file agree by construction rather than by luck.
        transfer.decline("Withdrawn by the customer while the review was open", at);
        transfers.add(transfer);
        accounts.save(source);

        FraudAlert alert = new FraudAlert(
                alerts.nextId(),
                transfer.id(),
                "New beneficiary + high amount",
                65,
                DEMO_ANALYST,
                null);
        alert.approve(
                "Customer withdrew the payment before we called. Nothing further to chase.",
                DEMO_ANALYST,
                at);
        alerts.add(alert);

        // A note that outlives the verdict, which is the whole reason the journal sits beside the
        // decision comment rather than inside it: the comment says what was decided about this
        // alert, and this says what the next analyst should do about the next one.
        note(alert, "Same payee as the open alert on this account. If a third payment to this "
                + "IBAN appears, take it to the scheme instead of clearing it again.");
    }

    /**
     * One line in an alert's case journal.
     *
     * Written at the alert's own creation instant rather than at some readable interval after it.
     * FraudAlert stamps that instant itself and the seed cannot move it, so anything earlier would
     * be a note that predates the alert it is filed against, and anything later would be dated in
     * the future for as long as it took somebody to open the screen. Two notes therefore share a
     * minute, which is what two lines typed one after the other look like anyway; both repositories
     * break the tie on insertion order, so the journal keeps the order it was written in.
     */
    private void note(FraudAlert alert, String text) {
        alerts.appendNote(new FraudAlertNote(alert.id(), DEMO_ANALYST, alert.createdAt(), text));
    }

    /**
     * A payment the bank has asked the customer to confirm, so the screen that takes a one-time
     * code has something to take it for.
     *
     * Waiting and not held, which is the other half of the distinction {@link #flagTransfer}
     * draws: that one is stopped by the bank and this one is stopped by the customer's own
     * confirmation step, and until now the dataset carried only the first. No alert is raised on
     * it, and nothing should be: the payee is trusted and the rules that raise one do not fire.
     */
    private void awaitTransfer(Account source, Beneficiary target, Instant at) {
        Transfer transfer = newTransfer(source, target, AWAITING_AMOUNT, at);
        transfer.requestAuthorization(new CardPayment(transfer.amount(), "****0000"), AWAITING_WINDOW);
        transfers.add(transfer);
        accounts.save(source);
    }

    private Transfer newTransfer(Account source, Beneficiary target, Money amount, Instant at) {
        return new Transfer(
                transfers.nextId(),
                source.id(),
                target.id(),
                target.iban().value(),
                amount,
                at);
    }
}
