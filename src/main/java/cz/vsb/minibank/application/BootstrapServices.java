package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.domain.FraudAlertEvents;

public class BootstrapServices {

    public final TransferApplicationService transferService;
    public final FraudApplicationService fraudService;
    public final PaymentNetworkGateway paymentGateway; // for integration tests and possibly UI

    public BootstrapServices(CustomerRepository customers,
                             AccountRepository accounts,
                             TransferRepository transfers,
                             FraudAlertRepository alerts,
                             UnitOfWorkFactory uowFactory) {
        this(customers, accounts, transfers, alerts, uowFactory, false);
    }

    /**
     * @param demoMode when true, uses ZeroFeePolicy (no fees) as a Special Case.
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

        this.transferService = new TransferApplicationService(
                customers,
                accounts,
                transfers,
                alerts,
                feePolicy,
                riskService,
                otp,
                paymentGateway,
                uowFactory
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
