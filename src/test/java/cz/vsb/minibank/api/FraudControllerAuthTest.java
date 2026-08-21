package cz.vsb.minibank.api;

import cz.vsb.minibank.application.FraudApplicationService;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.exceptions.AccessDeniedException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FraudControllerAuthTest {

    /**
     * A unit of work that does nothing, which is all this test needs one to do.
     *
     * The read endpoints open one so that a real backend answers a whole screen on a single
     * connection. Here the repositories are mocks and have no connection, so the scope only has
     * to exist and close cleanly - which is itself worth asserting by construction: a controller
     * that opened a unit of work and failed to close it would wedge the JSON store.
     */
    private static UnitOfWorkFactory noOpUnitOfWork() {
        UnitOfWorkFactory factory = mock(UnitOfWorkFactory.class);
        when(factory.begin()).thenReturn(mock(UnitOfWork.class));
        return factory;
    }

    private static User fraudUser() {
        return new User(
                2,
                "fraud",
                new byte[]{3},
                new byte[]{4},
                UserRole.FRAUD_ANALYST,
                null
        );
    }

    private static User customerUser() {
        return new User(
                1,
                "alice",
                new byte[]{1},
                new byte[]{2},
                UserRole.CUSTOMER,
                42
        );
    }

    @Test
    void listAlertsAllowedForFraudAnalyst() {
        // arrange
        SecurityContext.setCurrentUser(fraudUser());

        FraudAlertRepository alerts = mock(FraudAlertRepository.class);
        TransferRepository transfers = mock(TransferRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        FraudApplicationService fraudService = mock(FraudApplicationService.class);
        FeePolicy feePolicy = new ZeroFeePolicy();

        // Real fraud alert entity
        FraudAlert alert = new FraudAlert(
                1,
                10,             // transferId
                "test",
                80,
                "fraud",
                List.of("tag"),
                "notes"
        );
        alert.hydrateForLoad(
                FraudAlertState.NEW,
                "test",
                Instant.now(),
                80,
                "fraud",
                List.of("tag"),
                "notes"
        );

        // Real transfer so it is not filtered out
        Transfer transfer = new Transfer(
                10,                               // id
                100,                              // sourceAccountId
                null,                             // beneficiaryId
                "CZ2001000000000012345678",       // targetIbanSnapshot
                Money.czk(1000)                   // amount
        );

        // The queue asks the store for a page of rows, for how many the filter matched, and for
        // the whole queue by state. Three answers, and the last is deliberately not derived from
        // the first two.
        when(alerts.queuePage(any(), anyInt(), anyInt())).thenReturn(List.of(
                new FraudAlertRepository.QueueRow(alert, transfer.id(), transfer.status(),
                        transfer.amount())));
        when(alerts.queueTotal(any())).thenReturn(1);
        when(alerts.countByState()).thenReturn(Map.of(FraudAlertState.NEW, 1));

        FraudController ctrl = new FraudController(
                alerts,
                transfers,
                accounts,
                customers,
                fraudService,
                feePolicy,
                noOpUnitOfWork()
        );

        // act
        var resp = ctrl.listAlerts(null, null, null, null, null, null, null, null, null);

        // assert: call succeeds and the alert is included in the result
        assertNotNull(resp);
        assertEquals(1, resp.alerts().items().size());
        assertEquals(1, resp.alerts().total());
        assertEquals(1, resp.counters().newCount()); // extra check on counters
    }

    @Test
    void listAlertsForbiddenForCustomer() {
        // arrange
        SecurityContext.setCurrentUser(customerUser());

        FraudAlertRepository alerts = mock(FraudAlertRepository.class);
        TransferRepository transfers = mock(TransferRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        FraudApplicationService fraudService = mock(FraudApplicationService.class);
        FeePolicy feePolicy = new ZeroFeePolicy();

        FraudController ctrl = new FraudController(
                alerts,
                transfers,
                accounts,
                customers,
                fraudService,
                feePolicy,
                noOpUnitOfWork()
        );

        // act + assert
        assertThrows(
                AccessDeniedException.class,
                () -> ctrl.listAlerts(null, null, null, null, null, null, null, null, null)
        );
    }

    /**
     * The two endpoints the queue test did not cover.
     *
     * FraudApplicationService does not check the role itself - the gate is in its callers, and
     * this controller is the only one of them reachable over HTTP. That makes these assertions
     * the gate rather than a duplicate of it, which is why they are worth writing down: the
     * mutating route in particular had nothing standing behind it in the suite.
     *
     * Refused before the body is read, so the request payload is irrelevant and passed as null.
     */
    @Test
    void decidingOnAnAlertIsForbiddenForCustomer() {
        SecurityContext.setCurrentUser(customerUser());

        FraudController ctrl = new FraudController(
                mock(FraudAlertRepository.class),
                mock(TransferRepository.class),
                mock(AccountRepository.class),
                mock(CustomerRepository.class),
                mock(FraudApplicationService.class),
                new ZeroFeePolicy(),
                noOpUnitOfWork()
        );

        assertThrows(AccessDeniedException.class, () -> ctrl.decide(1, null));
    }

    @Test
    void openingOneAlertIsForbiddenForCustomer() {
        SecurityContext.setCurrentUser(customerUser());

        FraudController ctrl = new FraudController(
                mock(FraudAlertRepository.class),
                mock(TransferRepository.class),
                mock(AccountRepository.class),
                mock(CustomerRepository.class),
                mock(FraudApplicationService.class),
                new ZeroFeePolicy(),
                noOpUnitOfWork()
        );

        assertThrows(AccessDeniedException.class, () -> ctrl.getAlert(1));
    }

    /**
     * The four routes added since the cases above, gated the same way and asserted the same way.
     *
     * Two of them write, and one of those writes a name into an audit-facing column, so they are
     * worth pinning individually rather than trusting to the class they live in: the gate is in
     * this controller and nowhere behind it, because FraudApplicationService deliberately checks
     * no role of its own.
     */
    @Test
    void theAssignmentTheHistoryAndTheHiddenQueueAreAllForbiddenForCustomer() {
        SecurityContext.setCurrentUser(customerUser());

        FraudController ctrl = new FraudController(
                mock(FraudAlertRepository.class),
                mock(TransferRepository.class),
                mock(AccountRepository.class),
                mock(CustomerRepository.class),
                mock(FraudApplicationService.class),
                new ZeroFeePolicy(),
                noOpUnitOfWork()
        );

        assertThrows(AccessDeniedException.class, () -> ctrl.takeAlert(1));
        assertThrows(AccessDeniedException.class, () -> ctrl.releaseAlert(1));
        assertThrows(AccessDeniedException.class, () -> ctrl.getAlertHistory(1, null, null));
        assertThrows(AccessDeniedException.class, () -> ctrl.listHiddenAlerts(
                null, null, null, null, null, null, null, null, null));
    }

    /**
     * An analyst takes an alert into their own name and no other, whatever the request says.
     *
     * There is no assignee on the wire at all: the route carries no body, so the only name it can
     * write is the one on the session. That is the same rule the decision route follows for
     * decided_by, and it is what makes an open assignment route safe - an analyst can claim work
     * and give it back, and cannot put a colleague's name on anything.
     */
    @Test
    void takingAnAlertRecordsTheSignedInAnalystAndNothingElse() {
        SecurityContext.setCurrentUser(fraudUser());

        FraudApplicationService fraudService = mock(FraudApplicationService.class);
        FraudAlertRepository alerts = mock(FraudAlertRepository.class);
        when(alerts.byId(anyInt())).thenReturn(Optional.empty());

        FraudController ctrl = new FraudController(
                alerts,
                mock(TransferRepository.class),
                mock(AccountRepository.class),
                mock(CustomerRepository.class),
                fraudService,
                new ZeroFeePolicy(),
                noOpUnitOfWork()
        );

        // The alert is missing, so the read that follows the write raises. What is asserted is
        // the write that had already happened by then.
        assertThrows(cz.vsb.minibank.domain.exceptions.NotFoundException.class,
                () -> ctrl.takeAlert(7));
        verify(fraudService).assign(7, "fraud");

        assertThrows(cz.vsb.minibank.domain.exceptions.NotFoundException.class,
                () -> ctrl.releaseAlert(7));
        verify(fraudService).assign(7, null);
    }
}
