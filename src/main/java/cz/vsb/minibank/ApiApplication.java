package cz.vsb.minibank;

import cz.vsb.minibank.api.ApiStartupCheck;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bootstraps the MiniBank REST API using Spring Boot.
 */
@SpringBootApplication
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ApiApplication.class);

        // A listener and not a bean, because what it checks decides whether there is any point
        // building beans at all: it runs as soon as the configuration is resolved and before the
        // first one exists. See ApiStartupCheck.
        application.addListeners(new ApiStartupCheck());

        application.run(args);
    }
}
