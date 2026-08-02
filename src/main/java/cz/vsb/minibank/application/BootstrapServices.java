package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.domain.FraudAlertEvents;

/**
 * Aggregates core application services, policies and gateways used by the MiniBank application.
 */
public class BootstrapServices {

    public final TransferApplicationService transferService;
    public final FraudApplicationService fraudService;
    public final PaymentNetworkGateway paymentGateway; // for integration tests and possibly UI
    public final FeePolicy feePolicy;

    /**
     * The one ownership rule, exposed so the read endpoints of A4 answer the same way the
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
     * @param demoMode when true, uses ZeroFeePolicy (no fees) as a special case
     */
    public BootstrapServices(CustomerRepository customers,
                             AccountRepository accounts,
                             TransferRepository transfers,
                             FraudAlertRepository alerts,
                             UnitOfWorkFactory uowFactory,
                             boolean demoMode) {

        this(
                customers,
                accounts,
                transfers,
                alerts,
                demoMode ? new ZeroFeePolicy() : new SimpleFeePolicy(),
                new RuleBasedRiskService(),
                new FixedOtpValidator(),
                new FakePaymentNetworkGateway(),  // Service Stub
                uowFactory
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

        TransferEvents.register(new TransferAuditLogObserver());
        FraudAlertEvents.register(new FraudAlertAuditLogObserver());

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
                paymentGateway,
                uowFactory,
                ownershipGuard
        );

        this.fraudService = new FraudApplicationService(
                transfers,
                alerts,
                accounts,
                feePolicy,
                uowFactory
        );
    }
}
