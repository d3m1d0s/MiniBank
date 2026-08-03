package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AlertQueueResponseDto;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.SimpleFeePolicy;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The queue counters describe the whole queue; the list beside them describes the filter.
 *
 * Pinned because it is a design decision that looks like a bug from the outside - seven over a
 * list of three - and the obvious "fix" makes the screen worse. Counting the filtered list would
 * leave two of the three permanently zero, since both desks open filtered to NEW, and would
 * destroy the only thing an analyst reads them for: watching SUSPICIOUS rise while they work.
 */
class FraudQueueCountersTest {

    private FraudAlertRepository alerts;
    private TransferRepository transfers;
    private FraudController controller;

    @BeforeEach
    void setUp() {
        alerts = mock(FraudAlertRepository.class);
        transfers = mock(TransferRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);

        UnitOfWorkFactory uowFactory = mock(UnitOfWorkFactory.class);
        when(uowFactory.begin()).thenReturn(mock(UnitOfWork.class));

        controller = new FraudController(alerts, transfers, accounts, null,
                new SimpleFeePolicy(), uowFactory);

        SecurityContext.setCurrentUser(new User(2, "anna.analyst", new byte[]{1}, new byte[]{2},
                UserRole.FRAUD_ANALYST, null));

        // One alert in each state, so a filter on any one of them hides the other two.
        FraudAlert stillNew = alertOn(10);
        FraudAlert decidedOk = alertOn(11);
        decidedOk.approve("anna.analyst", Instant.now());
        FraudAlert decidedSuspicious = alertOn(12);
        decidedSuspicious.markSuspicious("looks wrong", "anna.analyst", Instant.now());

        when(alerts.all()).thenReturn(List.of(stillNew, decidedOk, decidedSuspicious));
        for (int transferId : new int[]{10, 11, 12}) {
            when(transfers.byId(transferId)).thenReturn(Optional.of(transferOf(transferId)));
        }
    }

    @Test
    void withNoFilterTheListAndTheCountersAgree() {
        AlertQueueResponseDto response = controller.listAlerts(null, null, null, null, null, null);

        assertEquals(3, response.items().size());
        assertEquals(1, response.counters().newCount());
        assertEquals(1, response.counters().okCount());
        assertEquals(1, response.counters().suspiciousCount());
    }

    @Test
    void aStateFilterShortensTheListAndLeavesTheCountersAlone() {
        AlertQueueResponseDto response = controller.listAlerts("NEW", null, null, null, null, null);

        assertEquals(1, response.items().size(), "the list is the filter");
        assertEquals(1, response.counters().newCount());
        assertEquals(1, response.counters().okCount(),
                "the counters are the queue, so the states filtered out still report themselves");
        assertEquals(1, response.counters().suspiciousCount());
    }

    @Test
    void anAmountFilterAlsoLeavesTheCountersAlone() {
        // The finding this was filed under named only the state filter. Every filter does it:
        // the counters are computed before any of them.
        AlertQueueResponseDto response = controller.listAlerts(
                null, new BigDecimal("999999"), null, null, null, null);

        assertEquals(0, response.items().size(), "nothing is that expensive");
        assertEquals(3, response.counters().newCount()
                        + response.counters().okCount()
                        + response.counters().suspiciousCount(),
                "an empty list does not mean an empty queue, and the counters must keep saying so");
    }

    private static FraudAlert alertOn(int transferId) {
        return new FraudAlert(transferId * 100, transferId, "New beneficiary + high amount");
    }

    private static Transfer transferOf(int id) {
        return new Transfer(id, 1, null, "CZ2001000000000012345678", Money.czk(1000), "CZK");
    }
}
