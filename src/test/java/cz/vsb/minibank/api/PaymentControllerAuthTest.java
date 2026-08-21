package cz.vsb.minibank.api;

import cz.vsb.minibank.application.OwnershipGuard;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.exceptions.NotAuthenticatedException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
                Money.czk(10000), Money.czk(5000), Money.czk(3000));
        when(accounts.byCustomerId(42)).thenReturn(List.of(acc));
        when(transfers.sentTotalBetween(eq(10), any(), any())).thenReturn(Money.czk(1200));

        PaymentController ctrl = new PaymentController(transferService, accounts,
                mock(OwnershipGuard.class), transfers, feePolicy,
                mock(UnitOfWorkFactory.class));

        // act
        var result = ctrl.listMyAccounts();

        // assert
        verify(accounts).byCustomerId(42);
        assertEquals(1, result.size());
        assertEquals(acc.iban().value(), result.get(0).iban());

        // The three facts that make a refusal explicable: what may leave today, where the code is
        // asked for, and how much of the day has gone. Without the last one the first two are
        // thresholds with nothing to measure against, which is how the two-tier behaviour came to
        // look arbitrary from the screen.
        assertEquals("5000.00", result.get(0).dailyLimit().amount());
        assertEquals("3000.00", result.get(0).softDailyThreshold().amount());
        assertEquals("1200.00", result.get(0).spentToday().amount());
        assertEquals("CZK", result.get(0).spentToday().currency());
    }

    /**
     * An account with no soft tier of its own says so, rather than being handed the bank-wide one.
     *
     * The default belongs to the bank and moves when the bank moves it. Printed on a row as if it
     * were a property of the account, it would be a number the customer could not act on and could
     * not find anywhere else.
     */
    @Test
    void anAccountWithNoSoftTierOfItsOwnSendsNullRatherThanTheBankWideDefault() {
        SecurityContext.setCurrentUser(customerUser());

        TransferApplicationService transferService = mock(TransferApplicationService.class);
        AccountRepository accounts = mock(AccountRepository.class);
        TransferRepository transfers = mock(TransferRepository.class);
        FeePolicy feePolicy = new ZeroFeePolicy();

        Account acc = new Account(11, new IBAN("CZ6508000000192000145399"),
                Money.czk(10000), Money.czk(5000));
        when(accounts.byCustomerId(42)).thenReturn(List.of(acc));
        when(transfers.sentTotalBetween(eq(11), any(), any())).thenReturn(Money.czk(0));

        PaymentController ctrl = new PaymentController(transferService, accounts,
                mock(OwnershipGuard.class), transfers, feePolicy,
                mock(UnitOfWorkFactory.class));

        assertNull(ctrl.listMyAccounts().get(0).softDailyThreshold());
    }

    /**
     * The day window is the banking day of Europe/Prague, which is the one the refusal is measured
     * over, and both accounts of one customer are asked about the same day.
     *
     * A UTC day would file every payment made after 22:00 in summer under tomorrow, so the form
     * would show a spend of zero on an account that had already used its allowance.
     */
    @Test
    void theDayTotalIsAskedForTheBankingDayAndOnceForTheWholeList() {
        SecurityContext.setCurrentUser(customerUser());

        TransferApplicationService transferService = mock(TransferApplicationService.class);
        AccountRepository accounts = mock(AccountRepository.class);
        TransferRepository transfers = mock(TransferRepository.class);

        Account first = new Account(10, new IBAN("CZ6508000000192000145399"),
                Money.czk(10000), Money.czk(5000));
        Account second = new Account(11, new IBAN("CZ4308000000192000145407"),
                Money.czk(10000), Money.czk(5000));
        when(accounts.byCustomerId(42)).thenReturn(List.of(first, second));
        when(transfers.sentTotalBetween(anyInt(), any(), any())).thenReturn(Money.czk(0));

        new PaymentController(transferService, accounts, mock(OwnershipGuard.class),
                transfers, new ZeroFeePolicy(), mock(UnitOfWorkFactory.class))
                .listMyAccounts();

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(transfers, times(2)).sentTotalBetween(anyInt(), from.capture(), to.capture());

        ZoneId zone = TransferApplicationService.BANK_ZONE;
        LocalDate day = LocalDate.ofInstant(from.getValue(), zone);

        assertEquals(day.atStartOfDay(zone).toInstant(), from.getValue(),
                "the window has to open at midnight in the bank's own zone");
        assertEquals(day.plusDays(1).atStartOfDay(zone).toInstant(), to.getValue(),
                "and close at the next one, so the range is exactly one banking day");
        assertEquals(from.getAllValues().get(0), from.getAllValues().get(1),
                "both accounts of one customer must be asked about the same day, or a list drawn"
                        + " a millisecond before midnight reports two different ones");
    }

    @Test
    void listMyAccountsWithoutUserThrowsNotAuthenticated() {
        // arrange
        SecurityContext.clear();

        TransferApplicationService transferService = mock(TransferApplicationService.class);
        AccountRepository accounts = mock(AccountRepository.class);
        TransferRepository transfers = mock(TransferRepository.class);
        FeePolicy feePolicy = new ZeroFeePolicy();

        PaymentController ctrl = new PaymentController(transferService, accounts,
                mock(OwnershipGuard.class), transfers, feePolicy,
                mock(UnitOfWorkFactory.class));

        // act + assert
        assertThrows(NotAuthenticatedException.class, ctrl::listMyAccounts);
    }
}
