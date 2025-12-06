package cz.vsb.minibank.api;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.FraudApplicationService;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.application.PaymentNetworkGateway;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import cz.vsb.minibank.domain.FeePolicy;


/**
 * Связывает инфраструктуру (Bootstrap) с Spring-контекстом.
 */
@Configuration
public class MinibankApiConfig {

    // 1) Поднимаем инфраструктуру в JSON-режиме
    @Bean
    public Bootstrap bootstrap() {
        // если у тебя другой путь/файл — поправь здесь
        return new Bootstrap("data/data.json");
    }

    // 2) Оборачиваем её в BootstrapServices (создаёт сервисы приложения)
    @Bean
    public BootstrapServices bootstrapServices(Bootstrap infra) {
        // demoMode = true → ZeroFeePolicy (без комиссий), FixedOtpValidator, FakePaymentNetworkGateway
        return new BootstrapServices(
                infra.customers,   // CustomerRepository
                infra.accounts,    // AccountRepository
                infra.transfers,   // TransferRepository
                infra.alerts,      // FraudAlertRepository
                infra.uowFactory,  // UnitOfWorkFactory
                false               // demoMode
        );
    }

    // 3) Отдаём TransferApplicationService в контроллеры
    @Bean
    public TransferApplicationService transferApplicationService(BootstrapServices services) {
        return services.transferService;
    }

    @Bean
    public FraudApplicationService fraudApplicationService(BootstrapServices services) {
        return services.fraudService;
    }

    // 4) Репозитории, если их удобно отдельно инжектить в контроллеры
    @Bean
    public AccountRepository accountRepository(Bootstrap infra) {
        return infra.accounts;
    }

    // 5) FeePolicy — нужен контроллеру, чтобы посчитать fee/charged
    @Bean
    public FeePolicy feePolicy(BootstrapServices services) {
        return services.feePolicy;
    }

    @Bean
    public TransferRepository transferRepository(Bootstrap infra) {
        return infra.transfers;
    }

    @Bean
    public FraudAlertRepository fraudAlertRepository(Bootstrap infra) {
        return infra.alerts;
    }

    @Bean
    public CustomerRepository customerRepository(Bootstrap infra) {
        return infra.customers;
    }

    // 5) (опционально) PaymentNetworkGateway, если понадобится где-то ещё
    @Bean
    public PaymentNetworkGateway paymentNetworkGateway(BootstrapServices services) {
        return services.paymentGateway;
    }
}
