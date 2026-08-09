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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The order and the size of the payment history on the analyst's alert screen.
 *
 * Neither was asserted anywhere before this class: no test in the suite looked at
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
                null, new SimpleFeePolicy(), infra.uowFactory);
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
}
