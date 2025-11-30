package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;

public class BootstrapServices {

    public final TransferApplicationService transferService;
    public final FraudApplicationService fraudService;

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

        FeePolicy feePolicy = demoMode ? new ZeroFeePolicy() : new SimpleFeePolicy();
        RiskService risk = new RuleBasedRiskService();
        OtpValidator otp = new FixedOtpValidator();
        PaymentNetworkGateway paymentGateway = new FakePaymentNetworkGateway();

        this.transferService = new TransferApplicationService(
                customers,
                accounts,
                transfers,
                alerts,
                feePolicy,
                risk,
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
