package cz.vsb.minibank.uow;

import cz.vsb.minibank.api.AuthorizationController;
import cz.vsb.minibank.api.PaymentController;
import cz.vsb.minibank.api.dto.AuthorizePaymentRequest;
import cz.vsb.minibank.api.dto.NewPaymentRequest;
import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.FixedOtpValidator;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A write endpoint answers out of the transaction that did the work, and reads nothing after it.
 *
 * All three used to call the application service, let it commit and close its unit of work, and
 * only then read the transfer and the account back to build the response. Three things were wrong
 * with that, and the one worth a test is the first: the balance in the response was read after
 * the commit, so another transaction could land in between and the customer was shown a figure
 * that was never the balance after their own payment.
 *
 * Asserted structurally rather than by racing two threads. A race would have to be provoked at
 * exactly the window between commit and re-read, and a test that passes or fails on that timing
 * measures the scheduler rather than the code. What the race was ever standing in for is this:
 * did any repository call happen with no unit of work in scope. After this change none does, and
 * a re-read reintroduced anywhere in a controller puts one back.
 */
class WritePathsAnswerFromTheirOwnTransactionTest {

    /**
     * Records the unit of work in scope for every call made through it.
     *
     * A decorator rather than a mock, because the endpoints have to keep working: the answers
     * come from the real JSON repositories underneath and only the observation is added. The same
     * device {@link ReadPathsRunInOneUnitOfWorkTest} uses, kept separate because the property
     * being asserted is the opposite one - there, that every call shared one unit of work; here,
     * that none happened outside one.
     */
    private static final class Witness {
        final List<UnitOfWork> seen = new ArrayList<>();

        void record() {
            seen.add(UowContext.current());
        }

        void everyCallWasInsideAUnitOfWork(String what) {
            assertFalse(seen.isEmpty(), what + " made no repository calls at all, so this test"
                    + " would pass for the wrong reason");
            for (int i = 0; i < seen.size(); i++) {
                assertTrue(seen.get(i) != null, what + " made call " + (i + 1) + " of "
                        + seen.size() + " with no unit of work in scope, so it read the store"
                        + " again after the transaction that did the work had closed");
            }
        }
    }

    private final Witness witness = new Witness();

    @TempDir
    Path tempDir;

    private Bootstrap infra;
    private PaymentController paymentController;
    private AuthorizationController authorizationController;
    private int accountId;

    private static final IBAN PAYER_IBAN = new IBAN("CZ6508000000192000145399");
    private static final String TARGET_IBAN = "CZ2001000000000012345678";

    /**
     * Above the soft tier, so the payment waits for a code instead of settling on creation, and
     * below the alert threshold, so confirming it is not answered with a review hold. Both
     * boundaries matter here: the point of these tests is a path that returns, and a held payment
     * throws instead.
     */
    private static final double WAITS_FOR_A_CODE = 6_500;

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());

        int customerId = seedCustomerWithAnAccount();

        AccountRepository accounts = watch(infra.accounts);
        TransferRepository transfers = watch(infra.transfers);
        FraudAlertRepository alerts = watch(infra.alerts);

        BootstrapServices services = new BootstrapServices(
                infra.customers, accounts, transfers, alerts, infra.uowFactory);

        paymentController = new PaymentController(services.transferService, accounts);
        authorizationController = new AuthorizationController(services.transferService, accounts,
                transfers, services.feePolicy, services.ownershipGuard, infra.uowFactory);

        SecurityContext.setCurrentUser(new User(1, "payer", new byte[]{1}, new byte[]{2},
                UserRole.CUSTOMER, customerId));
        witness.seen.clear();
    }

    @Test
    void creatingAPaymentReadsNothingAfterItsOwnTransaction() {
        paymentController.createPayment(
                new NewPaymentRequest(accountId, TARGET_IBAN, WAITS_FOR_A_CODE, "structural"));

        witness.everyCallWasInsideAUnitOfWork("Creating a payment");
        assertNull(UowContext.current(), "and it must close what it opened");
    }

    @Test
    void authorizingAPaymentReadsNothingAfterItsOwnTransaction() {
        int transferId = paymentController.createPayment(
                        new NewPaymentRequest(accountId, TARGET_IBAN, WAITS_FOR_A_CODE, "structural"))
                .getBody().transferId();
        witness.seen.clear();

        authorizationController.authorize(transferId,
                new AuthorizePaymentRequest(FixedOtpValidator.DEMO_OTP));

        witness.everyCallWasInsideAUnitOfWork("Authorizing a payment");
        assertNull(UowContext.current(), "and it must close what it opened");
    }

    /**
     * The path that changed most: cancelling never loaded the account at all, so the controller's
     * re-read was the first thing to touch it. It now loads it inside the transaction instead -
     * one read where there were two, and this time the balance it reports is the one the
     * cancellation left.
     */
    @Test
    void cancellingAPaymentReadsNothingAfterItsOwnTransaction() {
        int transferId = paymentController.createPayment(
                        new NewPaymentRequest(accountId, TARGET_IBAN, WAITS_FOR_A_CODE, "structural"))
                .getBody().transferId();
        witness.seen.clear();

        var result = authorizationController.cancel(transferId);

        witness.everyCallWasInsideAUnitOfWork("Cancelling a payment");
        assertNull(UowContext.current(), "and it must close what it opened");
        assertEquals("DECLINED", result.status());
    }

    // ------------------------------------------------------------------
    // fixture and plumbing
    // ------------------------------------------------------------------

    private int seedCustomerWithAnAccount() {
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            int customerId = infra.customers.nextId();
            Customer c = new Customer(customerId, "Payer", "payer@example.com",
                    new Address("Hlavni 1", "Ostrava"));
            infra.customers.save(c);

            accountId = infra.accounts.nextId();
            infra.accounts.save(new Account(accountId, PAYER_IBAN,
                    Money.czk(5_000_000), Money.czk(4_000_000), null));
            c.addAccountId(accountId);
            infra.customers.save(c);

            scope.uow().commit();
            return customerId;
        }
    }

    private AccountRepository watch(AccountRepository real) {
        return (AccountRepository) java.lang.reflect.Proxy.newProxyInstance(
                AccountRepository.class.getClassLoader(),
                new Class<?>[]{AccountRepository.class},
                (proxy, method, args) -> {
                    witness.record();
                    return invoke(real, method, args);
                });
    }

    private TransferRepository watch(TransferRepository real) {
        return (TransferRepository) java.lang.reflect.Proxy.newProxyInstance(
                TransferRepository.class.getClassLoader(),
                new Class<?>[]{TransferRepository.class},
                (proxy, method, args) -> {
                    witness.record();
                    return invoke(real, method, args);
                });
    }

    private FraudAlertRepository watch(FraudAlertRepository real) {
        return (FraudAlertRepository) java.lang.reflect.Proxy.newProxyInstance(
                FraudAlertRepository.class.getClassLoader(),
                new Class<?>[]{FraudAlertRepository.class},
                (proxy, method, args) -> {
                    witness.record();
                    return invoke(real, method, args);
                });
    }

    /** Unwraps the reflection layer so a domain exception arrives as itself. */
    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
