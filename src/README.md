```pgsql
VIS_project_minibank/
├─ data/
│  ├─ data.json
│  └─ demo.json
└─ src/
   ├─ main/
   │  └─ java/
   │     └─ cz/vsb/minibank/
   │        │                                       
   │        ├─ application/                         
   │        │  ├─ BootstrapServices.java            (class)  
   │        │  ├─ FixedOtpValidator.java            (class)
   │        │  ├─ FraudApplicationService.java      (class)
   │        │  ├─ OtpValidator.java                 (interface)
   │        │  └─ TransferApplicationService.java   (class)
   │        │
   │        ├─ demo/
   │        │  └─ DemoRunner.java       (class)
   │        │
   │        ├─ domain/
   │        │  ├─ exceptions/
   │        │  │  ├─ AuthorizationFailedException.java      (exception)
   │        │  │  ├─ DailyLimitExceededException.java       (exception)
   │        │  │  ├─ DomainException.java                   (exception)
   │        │  │  ├─ InsufficientFundsException.java        (exception)
   │        │  │  ├─ InvalidIbanException.java              (exception)
   │        │  │  └─ InvalidStateTransitionException.java   (exception)
   │        │  │
   │        │  ├─ repository/
   │        │  │  ├─ AccountRepository.java     (interface)
   │        │  │  ├─ CustomerRepository.java    (interface)
   │        │  │  ├─ FraudAlertRepository.java  (interface)
   │        │  │  └─ TransferRepository.java    (interface)
   │        │  │
   │        │  ├─ value/
   │        │  │  ├─ IBAN.java      (class)
   │        │  │  └─ Money.java     (class)
   │        │  │
   │        │  ├─ Account.java                  (class)
   │        │  ├─ Address.java                  (class)
   │        │  ├─ Beneficiary.java              (class)
   │        │  ├─ CardPayment.java              (class)
   │        │  ├─ Customer.java                 (class)
   │        │  ├─ FeePolicy.java                (interface)
   │        │  ├─ FraudAlert.java               (class)
   │        │  ├─ FraudAlertState.java          (enum)
   │        │  ├─ Payment.java                  (abstract)
   │        │  ├─ RiskDecision.java             (final)
   │        │  ├─ RiskService.java              (interface)
   │        │  ├─ RuleBasedRiskService.java     (interface)
   │        │  ├─ SimpleFeePolicy.java          (class)
   │        │  ├─ Transfer.java                 (class)
   │        │  └─ TransferStatus.java           (enum)
   │        │
   │        ├─ infrastructure/
   │        │  ├─ json/
   │        │  │  ├─ dto/
   │        │  │  │  ├─ JsonAccount.java        (class)
   │        │  │  │  ├─ JsonAddress.java        (class)
   │        │  │  │  ├─ JsonBeneficiary.java    (class)
   │        │  │  │  ├─ JsonCustomer.java       (class)
   │        │  │  │  ├─ JsonFraudAlert.java     (class)
   │        │  │  │  └─ JsonTransfer.java       (class)
   │        │  │  ├─ mapping/
   │        │  │  │  └─ JsonMapper.java     (class)
   │        │  │  ├─ repo/
   │        │  │  │  ├─ JsonAccountRepository.java      (class)
   │        │  │  │  ├─ JsonCustomerRepository.java     (class)
   │        │  │  │  ├─ JsonFraudAlertRepository.java   (class)
   │        │  │  │  └─ JsonTransferRepository.java     (class)
   │        │  │  └─ JsonDataStore.java    (class)
   │        │  │
   │        │  ├─ uow/
   │        │  │  ├─ JsonUnitOfWork.java            (class)
   │        │  │  ├─ JsonUnitOfWorkFactory.java     (class)
   │        │  │  ├─ UnitOfWork.java                (interface)
   │        │  │  ├─ JsonUnitOfWorkFactory.java     (interface)
   │        │  │  ├─ UowContext.java                (class)
   │        │  │  ├─ UowScope.java                  (class)
   │        │  │
   │        │  └─ Bootstrap.java   (class)
   │        │
   │        ├─ ui/console/
   │        │  └─ ConsoleMenu.java    (class)
   │        │
   │        └─ App.java      (class)
   │
   └─ test/
      └─ java/
         └─ cz/vsb/minibank/
            └─ uow/
               └─ MinibankUowTests.java     (unit test) 

```