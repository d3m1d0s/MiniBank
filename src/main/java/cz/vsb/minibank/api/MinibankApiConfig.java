package cz.vsb.minibank.api;

import cz.vsb.minibank.application.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.domain.FeePolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for MiniBank REST API wiring repositories, services and authentication.
 */
@Configuration
public class MinibankApiConfig {

    @Bean
    public Bootstrap bootstrap() {
        return new Bootstrap("data/data.json");
    }

    @Bean
    public BootstrapServices bootstrapServices(Bootstrap infra) {
        return new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory,
                false
        );
    }

    @Bean
    public TransferApplicationService transferApplicationService(BootstrapServices services) {
        return services.transferService;
    }

    @Bean
    public FraudApplicationService fraudApplicationService(BootstrapServices services) {
        return services.fraudService;
    }

    @Bean
    public AccountRepository accountRepository(Bootstrap infra) {
        return infra.accounts;
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

    @Bean
    public FeePolicy feePolicy(BootstrapServices services) {
        return services.feePolicy;
    }

    @Bean
    public PaymentNetworkGateway paymentNetworkGateway(BootstrapServices services) {
        return services.paymentGateway;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Pbkdf2PasswordEncoder();
    }

    @Bean
    public AuthService authService(Bootstrap infra, PasswordEncoder encoder) {
        return new AuthService(infra.users, encoder);
    }

    @Bean
    public SessionStore sessionStore() {
        return new SessionStore();
    }
}
