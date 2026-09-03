package cz.vsb.minibank.api.config;

import cz.vsb.minibank.application.audit.AppLogger;
import cz.vsb.minibank.application.config.MinibankProperties;
import cz.vsb.minibank.application.payment.PaymentDispatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * The two things the API does to the data at startup, in the order they have to happen and at a
 * point where a failure in either can be reported as itself.
 * <p>
 * Both used to run from {@code @PostConstruct}, which put them inside bean construction: a
 * database that refused the first write failed the bean, and Spring reported a context that could
 * not be built, several frames deep, with the seeding named nowhere in it. Here the context is
 * already up, so a failure is caught, said in one sentence and followed by what to do about it.
 * <p>
 * The order is a plain sequence rather than the arrangement of providers it replaces: the seed
 * commits a settled payment out of this bank, and that is precisely one of the rows the sweep
 * exists to find, so the seed goes first. The provider is still a provider because the seeding
 * bean only exists under the demo profile, and a run without it has nothing to wait for.
 * <p>
 * A failure stops the application. The alternative is an API that listens and answers every call
 * with a failure of its own, which is the state this replaced: whatever the operator has to fix,
 * they have to fix it before the process is worth having.
 */
public final class StartupSequence implements ApplicationListener<ApplicationReadyEvent> {

    private final PaymentDispatcher dispatcher;
    private final ObjectProvider<DemoUsersInitializer> demoData;

    StartupSequence(PaymentDispatcher dispatcher, ObjectProvider<DemoUsersInitializer> demoData) {
        this.dispatcher = dispatcher;
        this.demoData = demoData;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!runStartupSteps()) {
            stop(event);
        }
    }

    /**
     * Both steps, in order, and whether they finished. True when everything ran; false when one
     * step failed, in which case it has already been reported.
     *
     * Separated from the listener above for the reason {@code ApiStartupCheck.inspect} is: what
     * this decides can then be read back in a test, while the one thing that ends the process
     * stays in the one method a test never calls.
     */
    boolean runStartupSteps() {
        DemoUsersInitializer initializer = demoData.getIfAvailable();
        if (initializer != null && !run("the demo data could not be created", initializer::seedDemoData)) {
            return false;
        }

        return run("the payments this bank still owes the network could not be handed over",
                dispatcher::sweepPending);
    }

    /** True when the step finished; false when it failed and has been reported. */
    private boolean run(String whatFailed, Runnable step) {
        try {
            step.run();
            return true;
        } catch (RuntimeException e) {
            report(whatFailed, e);
            return false;
        }
    }

    /**
     * Printed to stdout, where the person who started the process is looking, and logged in one
     * line so the same event is in the log file with its stack trace. The block itself carries no
     * stack trace: what an operator needs from this is the sentence and the two commands.
     */
    private void report(String whatFailed, RuntimeException e) {
        String reason = ApiStartupCheck.firstLine(e.getMessage());
        AppLogger.error("api", "Stopping at startup: " + whatFailed + ": " + reason, e);

        System.out.println();
        System.out.println("=== MiniBank is stopping ===");
        System.out.println("The API started, then " + whatFailed + ".");
        System.out.println("Reason: " + reason);
        System.out.println();
        System.out.println("The store it is configured to use has to be reachable and carry the");
        System.out.println("schema. For PostgreSQL that is db/init/schema.sql, in the database");
        System.out.println(MinibankProperties.SQL_URL + " points at; docker compose up -d creates");
        System.out.println("both. To serve only the data that is already there, start with");
        System.out.println("--spring.profiles.active=plain, which seeds nothing.");
        System.out.flush();
    }

    /**
     * Through {@link SpringApplication#exit} rather than straight to {@code System.exit}, so the
     * context closes and the port is given back the way it would be on any other shutdown.
     */
    private void stop(ApplicationReadyEvent event) {
        System.exit(SpringApplication.exit(event.getApplicationContext(), () -> 1));
    }
}
