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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FraudControllerAuthTest {

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
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
                "CZ0201000000000012345678",       // targetIbanSnapshot
                Money.czk(1000),                  // amount
                "CZK"                             // currency
        );

        when(alerts.all()).thenReturn(List.of(alert));
        when(transfers.byId(10)).thenReturn(Optional.of(transfer));

        FraudController ctrl = new FraudController(
                alerts,
                transfers,
                accounts,
                fraudService,
                feePolicy
        );

        // act
        var resp = ctrl.listAlerts(null, null, null, null, null, null);

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
                feePolicy
        );

        // act + assert
        assertThrows(
                AccessDeniedException.class,
                () -> ctrl.listAlerts(null, null, null, null, null, null)
        );
    }
}
