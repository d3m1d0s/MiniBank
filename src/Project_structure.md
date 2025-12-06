```pgsql
VIS_project_minibank/
├─ .gitignore
├─ docker-compose.yml
├─ minibank.log
├─ pom.xml
├─ transfer_audit.log
│
├─ data/
│  ├─ data.json
│  └─ demo.json
├─ db/
│  └─ schema.sql
├─ minibank-web/
│  ├─ node_modules/...
│  ├─ public/
│  │  └─ vite.svg
│  ├─ src/
│  │  ├─ assets/
│  │  │  └─ react.svg
│  │  │  
│  │  ├─ api.ts 
│  │  ├─ App.css
│  │  ├─ App.tsx
│  │  ├─ index.css
│  │  ├─ main.tsx
│  │  ├─ NewPaymentPage.tsx
│  │  └─ WaitingAuthorizationsPage.tsx
│  │
│  ├─ .gitignore
│  ├─ eslint.config.js
│  ├─ index.html
│  ├─ package.json
│  ├─ package-lock.json
│  ├─ README.md
│  ├─ tsconfig.app.json
│  ├─ tsconfig.json
│  ├─ tsconfig.node.json
│  └─ vite.config.ts
│  
└─ src/
   ├─ main/
   │  └─ java/
   │     └─ cz/vsb/minibank/
   │        │                    
   │        ├─ api/    
   │        │  ├─ dto/
   │        │  │  ├─ AccountSummaryDto.java          (record)
   │        │  │  ├─ AuthorizePaymentRequest.java    (record)
   │        │  │  ├─ AuthorizePaymentResult.java     (record)
   │        │  │  ├─ NewPaymentRequest.java          (record)
   │        │  │  ├─ NewPaymentResultDto.java        (record)
   │        │  │  ├─ TransferDetailsDto.java         (record)
   │        │  │  └─ WaitingTransferItemDto.java     (record)   
   │        │  │   
   │        │  ├─ ApiError.java                   (record) 
   │        │  ├─ AuthorizationController.java    (class)                    
   │        │  ├─ MinibankApiConfig.java          (class)  
   │        │  ├─ PaymentController.java          (class)
   │        │  └─ RestExceptionHandler.java       (class)     
   │        │                   
   │        ├─ application/    
   │        │  ├─ AppLogger.java                    (class) 
   │        │  ├─ AuthService.java                  (class)                    
   │        │  ├─ BootstrapServices.java            (class)  
   │        │  ├─ FakePaymentNetworkGateway.java    (class)
   │        │  ├─ FixedOtpValidator.java            (class)
   │        │  ├─ FraudAlertAuditLogObserver.java   (class)
   │        │  ├─ FraudApplicationService.java      (class)
   │        │  ├─ HttpPaymentNetworkGateway.java    (class)
   │        │  ├─ LogLevel.java                     (enum)   
   │        │  ├─ OtpValidator.java                 (interface)
   │        │  ├─ PasswordEncoder.java              (interface)   
   │        │  ├─ PaymentNetworkGateway.java        (interface)
   │        │  ├─ Pbkdf2PasswordEncoder.java        (class)   
   │        │  ├─ SecurityContext.java              (class)    
   │        │  ├─ TransferApplicationService.java   (class)   
   │        │  └─ TransferAuditLogObserver.java     (class)
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
   │        │  ├─ lazy/
   │        │  │  ├─ LazyList.java      (class)
   │        │  │  ├─ LazyRef.java       (class)
   │        │  │
   │        │  ├─ repository/
   │        │  │  ├─ AccountRepository.java     (interface)
   │        │  │  ├─ CustomerRepository.java    (interface)
   │        │  │  ├─ FraudAlertRepository.java  (interface)
   │        │  │  ├─ TransferRepository.java    (interface)
   │        │  │  └─ UserRepository.java        (interface)   
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
   │        │  ├─ FraudAlertEvents.java         (class)   
   │        │  ├─ FraudAlertObserver.java       (class)    
   │        │  ├─ FraudAlertState.java          (enum)
   │        │  ├─ Payment.java                  (abstract)
   │        │  ├─ RiskDecision.java             (final)
   │        │  ├─ RiskService.java              (interface)
   │        │  ├─ RuleBasedRiskService.java     (interface)
   │        │  ├─ SimpleFeePolicy.java          (class)
   │        │  ├─ Transfer.java                 (class)
   │        │  ├─ TransferEvents.java           (class)
   │        │  ├─ TransferObserver.java         (interface)       
   │        │  ├─ TransferStatus.java           (enum)
   │        │  ├─ User.java                     (class) 
   │        │  ├─ UserRole.java                 (enum)  
   │        │  └─ ZeroFeePolicy.java            (class)
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
   │        │  │  ├─ JsonDataStore.java             (class)
   │        │  │  ├─ JsonUnitOfWork.java            (class)
   │        │  │  └─ JsonUnitOfWorkFactory.java     (class)
   │        │  │
   │        │  ├─ memory/
   │        │  │  └─ InMemoryUserRepository    (class)
   │        │  │   
   │        │  ├─ sql/
   │        │  │  ├─ repo/
   │        │  │  │  ├─ SqlAccountRepository.java      (class)
   │        │  │  │  ├─ SqlCustomerRepository.java     (class)
   │        │  │  │  ├─ SqlFraudAlertRepository.java   (class)
   │        │  │  │  ├─ SqlTransferRepository.java     (class)
   │        │  │  │  └─ SqlUserRepository.java         (class)   
   │        │  │  ├─ SqlUnitOfWork.java            (class)
   │        │  │  └─ SqlUnitOfWorkFactory.java     (class)
   │        │  │
   │        │  ├─ uow/
   │        │  │  ├─ UnitOfWork.java                (interface)
   │        │  │  ├─ JsonUnitOfWorkFactory.java     (interface)
   │        │  │  ├─ UowContext.java                (class)
   │        │  │  └─ UowScope.java                  (class)
   │        │  │
   │        │  └─ Bootstrap.java   (class)
   │        │
   │        ├─ ui/console/
   │        │  ├─ ConsoleCommand.java    (interface)   
   │        │  └─ ConsoleMenu.java       (class)
   │        │
   │        ├─ ApiApplication.java      (class)   
   │        ├─ App.java                 (class)
   │        └─ AppSql.java              (class)
   │
   └─ test/
      └─ java/
         └─ cz/vsb/minibank/
            ├─ application/
            │  └─ PaymentAndAuthorizationApiTest.java   (unit test)    
            │                   
            ├─ application/
            │  ├─ AppLoggerTest.java                     (unit test)            
            │  ├─ AuthServiceTest.java                   (unit test)            
            │  ├─ FakePaymentNetworkGatewayTest.java     (unit test) 
            │  ├─ Pbkdf2PasswordEncoderTest.java         (unit test)            
            │  └─ PaymentNetworkIntegrationTest.java     (unit test) 
            │   
            ├─ domain/
            │  ├─ FraudAlertEventsTest.java                   (unit test)     
            │  ├─ FraudAlertMetadataTest.java                 (unit test)            
            │  ├─ TransferEventsTest.java                     (unit test)             
            │  ├─ TransferStatusObserverIntegrationTest.java  (unit test) 
            │  └─ ZeroFeePolicyTest.java                      (unit test) 
            │   
            ├─ infrastructure/
            │  └─ json/  
            │     └─ FraudAlertJsonMapperTest.java      (unit test)   
            │                      
            ├─ ui/
            │  └─ console/      
            │     ├─ ConsoleMenuCommandTests.java      (unit test)  
            │     └─ ConsoleMenuRolesTest.java         (unit test)                                                   
            │
            └─ uow/
               ├─ MinibankLazyLoadTest.java     (unit test) 
               ├─ MinibankSqlUowTest.java       (unit test) 
               └─ MinibankUowTests.java         (unit test) 

```