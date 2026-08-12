package cz.vsb.minibank.api;

import cz.vsb.minibank.application.*;
import cz.vsb.minibank.demo.DemoScenario;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;
import cz.vsb.minibank.domain.FeePolicy;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

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

    /**
     * The API's composition root for services, and the one place it attaches the audit observers.
     *
     * The bus belongs to the Bootstrap bean and lives as long as the context does, so this has to
     * happen exactly once per context. A singleton bean method runs exactly once, which is what
     * makes this the right hook; it must not move back into {@code BootstrapServices}, which
     * anything may construct any number of times.
     */
    @Bean
    public BootstrapServices bootstrapServices(Bootstrap infra) {
        infra.events.register(new TransferAuditLogObserver());
        infra.events.register(new FraudAlertAuditLogObserver());

        return new BootstrapServices(
                infra.customers,
                infra.accounts,
                infra.transfers,
                infra.alerts,
                infra.uowFactory,
                false
        );
    }

    /**
     * The one dispatcher for this context, and the one place it is attached to the bus.
     *
     * A bean of its own rather than a third registration inside {@link #bootstrapServices}, for a
     * reason the audit observers do not have: the startup sweep needs the same instance, and a
     * singleton bean method is both the single registration point and the way to hand it over. It
     * is built from {@code services.paymentGateway}, so the dispatcher and anything else holding
     * the gateway bean are looking at one network.
     */
    @Bean
    public PaymentDispatcher paymentDispatcher(Bootstrap infra, BootstrapServices services) {
        PaymentDispatcher dispatcher = new PaymentDispatcher(
                services.paymentGateway, infra.transfers, infra.uowFactory);
        infra.events.register(dispatcher);
        return dispatcher;
    }

    /**
     * Runs the startup sweep, after the demo data is in place.
     *
     * The ordering is not left to bean-creation luck. {@code DemoUsersInitializer} seeds from its
     * own {@code @PostConstruct}, and the seed commits a settled payment out of this bank, which
     * is precisely one of the rows the sweep exists to find. Resolving the initializer here is
     * what puts the two in order: a bean handed out of an {@link ObjectProvider} is fully
     * initialized, so its seeding has finished before this method returns and therefore before the
     * returned bean's own {@code @PostConstruct} runs. An {@code ObjectProvider} rather than a
     * plain parameter or {@code @DependsOn} because the initializer only exists under the demo
     * profile, and without it there is simply nothing to wait for.
     */
    @Bean
    public PaymentDispatchSweep paymentDispatchSweep(PaymentDispatcher dispatcher,
                                                     ObjectProvider<DemoUsersInitializer> demoData) {
        demoData.getIfAvailable();
        return new PaymentDispatchSweep(dispatcher);
    }

    /**
     * The startup hook itself, following {@code DemoUsersInitializer} rather than inventing a
     * second pattern: this project has no {@code ApplicationRunner} anywhere and does not need its
     * first one to hand a handful of payments over.
     *
     * Nested in the configuration that builds the dispatcher so that the hook and the wiring that
     * orders it are read together. It carries no state and no logic of its own; everything it
     * knows is in {@link PaymentDispatcher#sweepPending()}.
     */
    public static final class PaymentDispatchSweep {

        private final PaymentDispatcher dispatcher;

        PaymentDispatchSweep(PaymentDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @PostConstruct
        void sweep() {
            dispatcher.sweepPending();
        }
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

    /**
     * Exposed so a controller can scope a read path to one unit of work.
     *
     * The write paths still do not need it, and that is now true for a better reason than it was:
     * they go through the application services, which open their own and hand back what they did
     * rather than leaving a controller to read it again afterwards. The read paths do need it -
     * without one, every repository call opens and tears down its own connection, and the alert
     * queue made one per alert.
     */
    @Bean
    public UnitOfWorkFactory unitOfWorkFactory(Bootstrap infra) {
        return infra.uowFactory;
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

    /**
     * infra.users is the same repository {@link #authService} is built from, so the check that
     * opens a session and the check that keeps it alive read one source.
     *
     * The clock is a parameter only so a test can advance time instead of sleeping; the store
     * measures elapsed time and never asks the clock for a calendar date, so its zone is
     * immaterial here and no Clock bean is added - there is none today and one would be inert.
     */
    @Bean
    public SessionStore sessionStore(Bootstrap infra) {
        return new SessionStore(infra.users, Clock.systemUTC());
    }

    /**
     * One throttle for the process, so a counter follows the caller across requests instead of
     * being reset by whichever bean handled the last one.
     *
     * The clock is a parameter for the reason {@link #sessionStore}'s is - a test advances a
     * window rather than waiting a quarter of an hour for one - and no Clock bean is added for
     * the same reason: this measures elapsed time and never asks for a calendar date, so its
     * zone is immaterial.
     */
    @Bean
    public LoginThrottle loginThrottle() {
        return new LoginThrottle(Clock.systemUTC());
    }
}
