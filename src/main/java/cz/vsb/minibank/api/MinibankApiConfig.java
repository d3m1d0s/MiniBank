package cz.vsb.minibank.api;

import cz.vsb.minibank.application.*;
import cz.vsb.minibank.demo.DemoScenario;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.domain.FeePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Spring configuration for MiniBank REST API wiring repositories, services and authentication.
 */
@Configuration
public class MinibankApiConfig {

    /**
     * Guards everything that exists only to make the project demonstrable:
     * the sample dataset and the demo logins. Active by default, see
     * application.properties.
     */
    public static final String DEMO_PROFILE = "demo";

    /**
     * The placeholders are assembled from {@link MinibankProperties} so that the API and the
     * console entry points cannot drift onto different key names or different defaults.
     */
    @Bean
    public Bootstrap bootstrap(
            @Value("${" + MinibankProperties.STORAGE + ":" + MinibankProperties.STORAGE_DEFAULT + "}") String storage,
            @Value("${" + MinibankProperties.JSON_PATH + ":" + MinibankProperties.JSON_PATH_DEFAULT + "}") String jsonPath,
            @Value("${" + MinibankProperties.SQL_URL + ":" + MinibankProperties.SQL_URL_DEFAULT + "}") String jdbcUrl,
            @Value("${" + MinibankProperties.SQL_USER + ":" + MinibankProperties.SQL_USER_DEFAULT + "}") String dbUser,
            @Value("${" + MinibankProperties.SQL_PASSWORD + ":" + MinibankProperties.SQL_PASSWORD_DEFAULT + "}") String dbPassword
    ) {
        if ("sql".equalsIgnoreCase(storage) || "postgres".equalsIgnoreCase(storage) || "postgresql".equalsIgnoreCase(storage)) {
            return new Bootstrap(jdbcUrl, dbUser, dbPassword);
        }
        if ("json".equalsIgnoreCase(storage)) {
            return new Bootstrap(jsonPath);
        }
        throw new IllegalArgumentException("Unsupported " + MinibankProperties.STORAGE + " value: " + storage);
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
    @Profile(DEMO_PROFILE)
    public DemoScenario demoScenario(Bootstrap infra, BootstrapServices services) {
        return new DemoScenario(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory,
                services.feePolicy
        );
    }

    @Bean
    public TransferApplicationService transferApplicationService(BootstrapServices services) {
        return services.transferService;
    }

    @Bean
    public OwnershipGuard ownershipGuard(BootstrapServices services) {
        return services.ownershipGuard;
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
