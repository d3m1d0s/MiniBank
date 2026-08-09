package cz.vsb.minibank.api;

import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.exceptions.NotAuthenticatedException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentControllerAuthTest {

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
    void listMyAccountsUsesCustomerFromSecurityContext() {
        // arrange
        SecurityContext.setCurrentUser(customerUser());

        TransferApplicationService transferService = mock(TransferApplicationService.class);
        AccountRepository accounts = mock(AccountRepository.class);
        TransferRepository transfers = mock(TransferRepository.class);
        FeePolicy feePolicy = new ZeroFeePolicy();

        Account acc = new Account(10, new IBAN("CZ6508000000192000145399"),
                Money.czk(10000), Money.czk(5000));
        when(accounts.byCustomerId(42)).thenReturn(List.of(acc));

        PaymentController ctrl = new PaymentController(transferService, accounts);

        // act
        var result = ctrl.listMyAccounts();

        // assert
        verify(accounts).byCustomerId(42);
        assertEquals(1, result.size());
        assertEquals(acc.iban().value(), result.get(0).iban());
    }

    @Test
    void listMyAccountsWithoutUserThrowsNotAuthenticated() {
        // arrange
        SecurityContext.clear();

        TransferApplicationService transferService = mock(TransferApplicationService.class);
        AccountRepository accounts = mock(AccountRepository.class);
        TransferRepository transfers = mock(TransferRepository.class);
        FeePolicy feePolicy = new ZeroFeePolicy();

        PaymentController ctrl = new PaymentController(transferService, accounts);

        // act + assert
        assertThrows(NotAuthenticatedException.class, ctrl::listMyAccounts);
    }
}
