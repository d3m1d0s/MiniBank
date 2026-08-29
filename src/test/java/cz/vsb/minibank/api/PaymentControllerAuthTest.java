package cz.vsb.minibank.api;

import cz.vsb.minibank.api.dto.AccountSummaryDto;
import cz.vsb.minibank.application.OwnershipGuard;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.exceptions.NotAuthenticatedException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    /**
     * A unit of work factory whose unit of work does nothing.
     *
     * The read endpoint below opens one, and the mocks behind it never touch a store, so an
     * instance that answers every method with Mockito's default is enough: what is being pinned
     * here is which customer id the controller reads, not what a transaction does.
     */
    private static UnitOfWorkFactory noOpUowFactory() {
        UnitOfWorkFactory factory = mock(UnitOfWorkFactory.class);
        when(factory.begin()).thenReturn(mock(UnitOfWork.class));
        return factory;
    }

    @Test
    void listMyAccountsUsesCustomerFromSecurityContext() {
        // arrange
        SecurityContext.setCurrentUser(customerUser());

        TransferApplicationService transferService = mock(TransferApplicationService.class);
        AccountRepository accounts = mock(AccountRepository.class);
        FeePolicy feePolicy = new ZeroFeePolicy();

        Account acc = new Account(10, new IBAN("CZ6508000000192000145399"), Money.czk(10000));
        when(accounts.byCustomerId(42)).thenReturn(List.of(acc));

        // The ceiling comes off the caller's customer row now, so the guard has to answer with
        // one. An unstubbed mock returns null and the controller would ask a null for its daily
        // limit, which is the shape this arrangement exists to keep out of the response.
        OwnershipGuard guard = mock(OwnershipGuard.class);
        when(guard.requireCaller(42)).thenReturn(new Customer(42, "Alice", "alice@example.com",
                new Address("Hlavni 1", "Ostrava"), Money.czk(40_000)));
        when(transferService.sentOutTodayBy(42)).thenReturn(Money.czk(1_500));

        PaymentController ctrl = new PaymentController(transferService, accounts,
                guard, feePolicy, noOpUowFactory());

        // act
        var result = ctrl.listMyAccounts();

        // assert
        verify(accounts).byCustomerId(42);
        assertEquals(1, result.accounts().size());
        assertEquals(acc.iban().value(), result.accounts().get(0).iban());

        // The day is the caller's, read from the session id and from nothing the request said.
        verify(guard).requireCaller(42);
        verify(transferService).sentOutTodayBy(42);
        assertEquals("1500.00", result.today().sentOut().amount());
        assertEquals("40000.00", result.today().limit().amount());
    }

    /**
     * One day for the whole response and not one per account.
     *
     * The defect the customer-wide ceiling exists to close, stated where a client would meet it:
     * two accounts share one allowance, so a shape that hung the total or the ceiling off each
     * account would be describing two allowances again. There is exactly one place in this body
     * for either number.
     */
    @Test
    void theDayIsOnTheResponseAndNotOnEachAccount() {
        SecurityContext.setCurrentUser(customerUser());

        TransferApplicationService transferService = mock(TransferApplicationService.class);
        AccountRepository accounts = mock(AccountRepository.class);

        when(accounts.byCustomerId(42)).thenReturn(List.of(
                new Account(10, new IBAN("CZ6508000000192000145399"), Money.czk(10_000)),
                new Account(11, new IBAN("CZ4308000000192000145407"), Money.czk(5_000))));

        OwnershipGuard guard = mock(OwnershipGuard.class);
        when(guard.requireCaller(42)).thenReturn(new Customer(42, "Alice", "alice@example.com",
                new Address("Hlavni 1", "Ostrava"), Money.czk(40_000)));
        when(transferService.sentOutTodayBy(42)).thenReturn(Money.czk(2_000));

        PaymentController ctrl = new PaymentController(transferService, accounts,
                guard, new ZeroFeePolicy(), noOpUowFactory());

        var result = ctrl.listMyAccounts();

        assertEquals(2, result.accounts().size());
        assertNotNull(result.today());
        assertEquals("2000.00", result.today().sentOut().amount());
        assertEquals("40000.00", result.today().limit().amount());

        // Asked once for the two accounts, which is the whole of what "one day per customer"
        // means on this route.
        verify(transferService, times(1)).sentOutTodayBy(42);

        // Counted off the record's own component list rather than by reading the three fields
        // that are there, which is what catches a fourth being added: a per-account day total is
        // the field somebody will reach for, and it would pass every assertion above.
        assertEquals(3, AccountSummaryDto.class.getRecordComponents().length,
                "an account summary carries id, iban and balance, and no day of its own");
    }

    @Test
    void listMyAccountsWithoutUserThrowsNotAuthenticated() {
        // arrange
        SecurityContext.clear();

        TransferApplicationService transferService = mock(TransferApplicationService.class);
        AccountRepository accounts = mock(AccountRepository.class);
        FeePolicy feePolicy = new ZeroFeePolicy();

        PaymentController ctrl = new PaymentController(transferService, accounts,
                mock(OwnershipGuard.class), feePolicy, noOpUowFactory());

        // act + assert
        assertThrows(NotAuthenticatedException.class, ctrl::listMyAccounts);
    }
}
