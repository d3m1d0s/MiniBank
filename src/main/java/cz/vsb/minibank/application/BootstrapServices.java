package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;

import java.time.Clock;

/**
 * Aggregates core application services, policies and gateways used by the MiniBank application.
 *
 * It deliberately registers no observers, although it used to register the two audit ones. This
 * class is a bag of services, not a composition root: the test suite builds it once per test
 * class that needs services, and the event buses are static, so every construction left another
 * pair of observers on them and one status change reached the audit log hundreds of times in a
 * single run. Attaching them belongs to the four places that actually start the application -
 * {@code App}, {@code AppSql}, {@code DemoRunner} and {@code MinibankApiConfig} - each of which
 * runs once. Those same four attach the {@link PaymentDispatcher}, for the same reason and from
 * the gateway this class exposes.
 */
public class BootstrapServices {

    public final TransferApplicationService transferService;
    public final FraudApplicationService fraudService;
    public final FeePolicy feePolicy;

    /**
     * The gateway the composition roots build their {@link PaymentDispatcher} from.
     *
     * Exposed rather than hidden because it is nobody's collaborator any more: no service here
     * holds it, since a settling payment records what it owes the network instead of calling it.
     * The one thing that does call it is attached to the event bus, by whatever started the
     * process, and this field is where it gets the instance from. The integration tests read it to
     * see what has been dispatched.
     */
    public final PaymentNetworkGateway paymentGateway;

    /**
     * The one ownership rule, exposed so the read endpoints answer the same way the
     * money-moving paths do instead of growing a second copy of it.
     */
    public final OwnershipGuard ownershipGuard;

    public BootstrapServices(CustomerRepository customers,
                             AccountRepository accounts,
                             TransferRepository transfers,
                             FraudAlertRepository alerts,
                             UnitOfWorkFactory uowFactory) {
        this(customers, accounts, transfers, alerts, uowFactory, false);
    }

    /**
     * Creates services with a configurable demo mode.
     *
     * @param demoMode when true, uses ZeroFeePolicy, which charges nothing. <strong>Nothing in
     *                 the running application passes true.</strong> The only caller that supplies
     *                 the flag at all is the Spring configuration, which passes a literal false,
     *                 and the console apps and the demo runner all reach this through the
     *                 five-argument constructor above. No property and no profile is wired to it.
     *                 So every fee any screen has ever shown came from SimpleFeePolicy, and
     *                 ZeroFeePolicy is reachable from tests only. It is kept because a test that
     *                 asserts a settled transfer keeps what it was charged needs two policies that
     *                 disagree, and because zero fees are what lets a balance assertion be about
     *                 the daily limit rather than about the fee. Turning this into a real mode
     *                 would be a feature, and one that makes every money figure on screen differ
     *                 from the tariff the bank documents.
     */
    public BootstrapServices(CustomerRepository customers,
                             AccountRepository accounts,
                             TransferRepository transfers,
                             FraudAlertRepository alerts,
                             UnitOfWorkFactory uowFactory,
                             boolean demoMode) {
        this(customers, accounts, transfers, alerts, uowFactory, demoMode,
                Clock.system(TransferApplicationService.BANK_ZONE));
    }

    /**
     * @param clock supplies "now" for transfer creation and for the banking-day totals. The
     *              only reason to pass anything but the system clock is a test that has to
     *              place payments on two different days without waiting for one to pass.
     */
    public BootstrapServices(CustomerRepository customers,
                             AccountRepository accounts,
                             TransferRepository transfers,
                             FraudAlertRepository alerts,
                             UnitOfWorkFactory uowFactory,
                             boolean demoMode,
                             Clock clock) {

        this(
                customers,
                accounts,
                transfers,
                alerts,
                demoMode ? new ZeroFeePolicy() : new SimpleFeePolicy(),
                new RuleBasedRiskService(),
                new FixedOtpValidator(),
                new FakePaymentNetworkGateway(),  // Service Stub
                uowFactory,
                clock
        );
    }

    public BootstrapServices(CustomerRepository customers,
                             AccountRepository accounts,
                             TransferRepository transfers,
                             FraudAlertRepository alerts,
                             FeePolicy feePolicy,
                             RiskService riskService,
                             OtpValidator otp,
                             PaymentNetworkGateway paymentGateway,
                             UnitOfWorkFactory uowFactory) {
        this(customers, accounts, transfers, alerts, feePolicy, riskService, otp,
                paymentGateway, uowFactory, Clock.system(TransferApplicationService.BANK_ZONE));
    }

    public BootstrapServices(CustomerRepository customers,
                             AccountRepository accounts,
                             TransferRepository transfers,
                             FraudAlertRepository alerts,
                             FeePolicy feePolicy,
                             RiskService riskService,
                             OtpValidator otp,
                             PaymentNetworkGateway paymentGateway,
                             UnitOfWorkFactory uowFactory,
                             Clock clock) {

        this.paymentGateway = paymentGateway;
        this.feePolicy = feePolicy;
        this.ownershipGuard = new OwnershipGuard(customers, accounts);

        this.transferService = new TransferApplicationService(
                accounts,
                transfers,
                alerts,
                feePolicy,
                riskService,
                otp,
                uowFactory,
                ownershipGuard,
                clock
        );

        // The same clock instance the transfer service got, so an alert's resolved_at and its
        // transfer's settled_at are readings of one clock rather than of two.
        this.fraudService = new FraudApplicationService(
                transfers,
                alerts,
                uowFactory,
                clock
        );
    }
}
