package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AlertDetailDto;
import cz.vsb.minibank.api.dto.HistoryItemDto;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.SimpleFeePolicy;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The order, the size and the reach of the payment history on the analyst's alert screen.
 *
 * None of the three was asserted anywhere before this class: no test in the suite looked at
 * {@code AlertDetailDto.history()} at all, while both fraud desks render the server's order
 * verbatim under a heading that promises the last ten. The sort was also composed wrongly - a
 * null rule reversed along with the order - and nothing would have caught it.
 *
 * There is deliberately no test here for a null creation instant. Building one means defeating
 * {@code Transfer}'s own constructor with reflection, which would pin the workaround rather than
 * the system. That a transfer cannot carry a null instant is the invariant this screen relies
 * on, and it is stated where it is enforced.
 */
class FraudHistoryOrderTest {

    private static final IBAN PAYER_IBAN = new IBAN("CZ6508000000192000145399");

    /** The customer's other account: the one the alert is not raised on, and the point below. */
    private static final IBAN SECOND_IBAN = new IBAN("CZ4308000000192000145407");

    private static final String TARGET_IBAN = "CZ2001000000000012345678";

    /** A fixed base, so the seeded instants are distinct by construction rather than by luck. */
    private static final Instant BASE = Instant.parse("2026-03-01T09:00:00Z");

    @TempDir
    Path tempDir;

    private Bootstrap infra;
    private FraudController fraudController;

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());
        fraudController = new FraudController(infra.alerts, infra.transfers, infra.accounts,
                infra.customers, null, new SimpleFeePolicy(), infra.uowFactory);
        SecurityContext.setCurrentUser(new User(2, "anna.analyst", new byte[]{1}, new byte[]{2},
                UserRole.FRAUD_ANALYST, null));
    }

    @Test
    void theHistoryIsNewestFirst() {
        seedHistory(5);

        List<HistoryItemDto> history = historyOfTheFirstAlert();

        assertEquals(5, history.size());
        for (int i = 1; i < history.size(); i++) {
            Instant earlier = Instant.parse(history.get(i).createdAt());
            Instant later = Instant.parse(history.get(i - 1).createdAt());
            assertTrue(later.isAfter(earlier),
                    "row " + (i - 1) + " must be newer than row " + i
                            + ", but " + later + " does not come after " + earlier);
        }
    }

    /**
     * The seam that makes this test possible is the seven-argument constructor: the six-argument
     * one stamps {@code Instant.now()}, and eleven of those in a loop are not reliably distinct.
     */
    @Test
    void theHistoryKeepsTheNewestTenAndDropsTheRest() {
        seedHistory(11);

        List<HistoryItemDto> history = historyOfTheFirstAlert();

        assertEquals(10, history.size(), "the screen promises the last ten");
        assertEquals(BASE.plusSeconds(10 * 60).toString(), history.get(0).createdAt(),
                "the newest payment must be the first row");
        assertEquals(BASE.plusSeconds(60).toString(), history.get(9).createdAt(),
                "the tenth row must be the second-oldest payment, not the oldest");
        assertTrue(history.stream().noneMatch(h -> BASE.toString().equals(h.createdAt())),
                "the oldest payment is the one that falls off the end");
    }

    /**
     * Every money value the analyst is sent carries its own currency.
     *
     * The desk used to be given bare numbers plus one currency field per response, and both
     * implementations appended it to the amount and to the history rows and forgot it on the fee
     * and on the source balance - so a fee was printed with no unit directly beneath an amount
     * that had one. Asserting the fee and the balance specifically, because those are the two
     * that were wrong; the amount was never the failing case.
     */
    @Test
    void everyMoneyValueOnTheAnalystsScreenNamesItsCurrency() {
        seedHistory(1);

        AlertDetailDto detail = fraudController.getAlert(infra.alerts.all().get(0).id());

        assertEquals("CZK", detail.transfer().feeAmount().currency());
        assertEquals("CZK", detail.transfer().fromBalance().currency());
        assertEquals("CZK", detail.transfer().amount().currency());
        assertEquals("CZK", detail.history().get(0).amount().currency());
    }

    /**
     * A payment the desk is holding is priced by the tariff, so its row carries a fee like every
     * other row of the table.
     *
     * These are the rows an analyst actually reads, and the whole reason to read them is that some
     * of what they list was stopped. Held and refused payments are therefore most of this table,
     * and while the row carried the charge alone every one of them arrived without a fee: the
     * column was blank down the majority of the desk. The seeded payment is held, nothing has been
     * taken on it, and 145.00 is what this controller's tariff answers for its amount.
     *
     * The two readings are told apart by the status in the next cell and by nothing else in the
     * row. A fee beside {@code HELD_FOR_REVIEW} is what the payment would cost if it were
     * released, not money the customer has paid, which is why the status is asserted here beside
     * it rather than left to the screen.
     */
    @Test
    void aHeldPaymentIsPricedByTheTariffRatherThanLeavingItsFeeBlank() {
        seedHistory(1);

        HistoryItemDto held = historyOfTheFirstAlert().get(0);

        assertNotNull(held.fee(),
                "the payment is held, and a held row with no fee at all is one an analyst cannot"
                        + " tell from a row whose fee went missing on the way to the screen");
        assertEquals(new SimpleFeePolicy().compute(Money.czk(12_000)).amount().toPlainString(),
                held.fee().amount(),
                "nothing has been charged on it, so what it would be charged is the only honest"
                        + " answer its row can give");
        assertEquals("HELD_FOR_REVIEW", held.status(),
                "and that answer is a price rather than a receipt, which is a distinction this"
                        + " row makes with its status and with nothing else");
    }

    /**
     * The history beside an alert covers every account the customer holds, and every row says
     * which of them its own payment left.
     *
     * The demo data cannot show either half: all of its payments leave one account, so the old
     * scope and this one answer with the same rows and the source column repeats one number.
     * That is why this is asserted here rather than looked at on the screen.
     *
     * The reach is the point of the panel. The question the analyst asks of this table is whether
     * a payment is out of character, and character belongs to a person, not to an account:
     * splitting one sum across the accounts one person holds, so that no part of it crosses a
     * threshold, is exactly what a table scoped to one account cannot show. The domain already
     * models that move, in SplitPaymentAlertTest.
     */
    @Test
    void theHistoryCoversEveryAccountTheCustomerHoldsAndNamesTheOneEachPaymentLeft() {
        Map<Integer, String> sourceIbanByTransfer = seedTwoAccounts();

        List<HistoryItemDto> history = historyOfTheFirstAlert();

        assertEquals(4, history.size(),
                "all four payments belong to the customer behind the alert, so all four are"
                        + " theirs to answer for");
        assertTrue(history.stream().anyMatch(h -> SECOND_IBAN.value().equals(h.fromIban())),
                "the account the alert was not raised on is the one the old scope dropped, so"
                        + " its payments are what proves the scope widened");

        for (HistoryItemDto item : history) {
            assertEquals(sourceIbanByTransfer.get(item.id()), item.fromIban(),
                    "row " + item.id() + " must name the account its own payment left, not the"
                            + " account the alert was raised on");
        }

        assertEquals(
                List.of(BASE.plusSeconds(180).toString(),
                        BASE.plusSeconds(120).toString(),
                        BASE.plusSeconds(60).toString(),
                        BASE.toString()),
                history.stream().map(HistoryItemDto::createdAt).toList(),
                "newest first has to hold across the mix of accounts, not within each of them");
    }

    private List<HistoryItemDto> historyOfTheFirstAlert() {
        int alertId = infra.alerts.all().get(0).id();
        AlertDetailDto detail = fraudController.getAlert(alertId);
        return new ArrayList<>(detail.history());
    }

    /**
     * Seeds one account with {@code howMany} held transfers, minute by minute from BASE, each
     * carrying an alert. Written through the repositories: what this observes is the read path.
     */
    private void seedHistory(int howMany) {
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            int customerId = infra.customers.nextId();
            Customer c = new Customer(customerId, "History Probe", "history@example.com",
                    new Address("Hlavni 1", "Ostrava"));
            infra.customers.save(c);

            int accountId = infra.accounts.nextId();
            infra.accounts.save(new Account(accountId, PAYER_IBAN,
                    Money.czk(5_000_000), Money.czk(4_000_000), null));
            c.addAccountId(accountId);
            infra.customers.save(c);

            for (int i = 0; i < howMany; i++) {
                Transfer t = new Transfer(infra.transfers.nextId(), accountId, null,
                        TARGET_IBAN, Money.czk(12_000), BASE.plusSeconds(i * 60L));
                t.holdForReview(null);
                infra.transfers.add(t);
                infra.alerts.add(new FraudAlert(infra.alerts.nextId(), t.id(),
                        "New beneficiary + high amount"));
            }

            scope.uow().commit();
        }
    }

    /**
     * Seeds one customer holding two accounts, with four held transfers alternating between them
     * minute by minute from BASE, and one alert on the oldest payment of the first account.
     *
     * The payments alternate so that the expected order is one neither account produces on its
     * own: a merge that kept the accounts apart would still pass a size check and still name the
     * right IBANs, and would fail here.
     *
     * The alert sits on a payment of the first account because that is the only route this panel
     * has to a customer - the account under the alerted transfer - so the second account has to
     * be reached through the customer or not at all.
     *
     * @return the source IBAN of every seeded transfer, by transfer id, in seeding order
     */
    private Map<Integer, String> seedTwoAccounts() {
        Map<Integer, String> sourceIbanByTransfer = new LinkedHashMap<>();

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            int customerId = infra.customers.nextId();
            Customer c = new Customer(customerId, "Two Account Probe", "two@example.com",
                    new Address("Hlavni 1", "Ostrava"));
            infra.customers.save(c);

            int firstAccountId = infra.accounts.nextId();
            infra.accounts.save(new Account(firstAccountId, PAYER_IBAN,
                    Money.czk(5_000_000), Money.czk(4_000_000), null));
            c.addAccountId(firstAccountId);

            int secondAccountId = infra.accounts.nextId();
            infra.accounts.save(new Account(secondAccountId, SECOND_IBAN,
                    Money.czk(5_000_000), Money.czk(4_000_000), null));
            c.addAccountId(secondAccountId);

            infra.customers.save(c);

            int[] sourceAccountIds = {firstAccountId, secondAccountId,
                    firstAccountId, secondAccountId};
            IBAN[] sourceIbans = {PAYER_IBAN, SECOND_IBAN, PAYER_IBAN, SECOND_IBAN};

            int alertedTransferId = 0;
            for (int i = 0; i < sourceAccountIds.length; i++) {
                Transfer t = new Transfer(infra.transfers.nextId(), sourceAccountIds[i], null,
                        TARGET_IBAN, Money.czk(12_000), BASE.plusSeconds(i * 60L));
                t.holdForReview(null);
                infra.transfers.add(t);
                sourceIbanByTransfer.put(t.id(), sourceIbans[i].value());
                if (i == 0) {
                    alertedTransferId = t.id();
                }
            }

            infra.alerts.add(new FraudAlert(infra.alerts.nextId(), alertedTransferId,
                    "New beneficiary + high amount"));

            scope.uow().commit();
        }

        return sourceIbanByTransfer;
    }
}
