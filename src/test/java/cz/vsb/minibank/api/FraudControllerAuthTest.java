package cz.vsb.minibank.api;

import cz.vsb.minibank.application.FraudApplicationService;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.exceptions.AccessDeniedException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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

        when(alerts.all()).thenReturn(List.of(alert));
        when(transfers.byId(10)).thenReturn(Optional.of(transfer));

        FraudController ctrl = new FraudController(
                alerts,
                transfers,
                accounts,
                fraudService,
                feePolicy,
                noOpUnitOfWork()
        );

        // act
        var resp = ctrl.listAlerts(null, null, null, null, null, null, null);

        // assert: call succeeds and the alert is included in the result
        assertNotNull(resp);
        assertEquals(1, resp.items().size());
        assertEquals(1, resp.counters().newCount()); // extra check on counters
    }

    @Test
    void listAlertsForbiddenForCustomer() {
        // arrange
        SecurityContext.setCurrentUser(customerUser());

        FraudAlertRepository alerts = mock(FraudAlertRepository.class);
        TransferRepository transfers = mock(TransferRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        FraudApplicationService fraudService = mock(FraudApplicationService.class);
        FeePolicy feePolicy = new ZeroFeePolicy();

        FraudController ctrl = new FraudController(
                alerts,
                transfers,
                accounts,
                fraudService,
                feePolicy,
                noOpUnitOfWork()
        );

        // act + assert
        assertThrows(
                AccessDeniedException.class,
                () -> ctrl.listAlerts(null, null, null, null, null, null, null)
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
                mock(FraudApplicationService.class),
                new ZeroFeePolicy(),
                noOpUnitOfWork()
        );

        assertThrows(AccessDeniedException.class, () -> ctrl.getAlert(1));
    }
}
