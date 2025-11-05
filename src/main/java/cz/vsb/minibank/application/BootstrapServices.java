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
        FeePolicy feePolicy = new SimpleFeePolicy();
        RiskService risk = new RuleBasedRiskService();
        OtpValidator otp = new FixedOtpValidator();
        this.transferService = new TransferApplicationService(customers, accounts, transfers, alerts, feePolicy, risk, otp, uowFactory);
        this.fraudService    = new FraudApplicationService(transfers, alerts, accounts, feePolicy, uowFactory);;
    }
}