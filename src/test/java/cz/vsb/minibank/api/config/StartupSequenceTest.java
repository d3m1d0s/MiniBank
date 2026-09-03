package cz.vsb.minibank.api.config;

import cz.vsb.minibank.application.payment.PaymentDispatcher;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two things the API does to the data at startup: that they happen in the order that makes
 * them correct, and that a failure in either is said out loud.
 * <p>
 * Both used to run from {@code @PostConstruct}. A database that refused the first write failed the
 * bean, and what reached the console was a context that could not be built, several frames deep,
 * with the word seeding nowhere in it. The order was luck of the same kind: it came from one bean
 * method resolving an {@code ObjectProvider} before returning.
 */
class StartupSequenceTest {

    private final PaymentDispatcher dispatcher = mock(PaymentDispatcher.class);
    private final DemoUsersInitializer demoData = mock(DemoUsersInitializer.class);

    @SuppressWarnings("unchecked")
    private ObjectProvider<DemoUsersInitializer> provides(DemoUsersInitializer initializer) {
        ObjectProvider<DemoUsersInitializer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(initializer);
        return provider;
    }

    /**
     * The seed commits a settled payment out of this bank, which is precisely one of the rows the
     * sweep exists to find. Seeding second would leave that payment owed to the network until
     * something else settled one.
     */
    @Test
    void theSeedGoesInBeforeThePaymentsAreHandedOver() {
        StartupSequence sequence = new StartupSequence(dispatcher, provides(demoData));

        assertTrue(sequence.runStartupSteps());

        InOrder order = inOrder(demoData, dispatcher);
        order.verify(demoData).seedDemoData();
        order.verify(dispatcher).sweepPending();
    }

    /** A run with no demo profile has no seeding bean, and nothing to wait for. */
    @Test
    void aRunWithoutTheDemoProfileStillHandsThePaymentsOver() {
        StartupSequence sequence = new StartupSequence(dispatcher, provides(null));

        assertTrue(sequence.runStartupSteps());

        verify(dispatcher).sweepPending();
    }

    /**
     * A seed that fails stops the start, and does not leave the sweep to run against half a
     * dataset.
     */
    @Test
    void aSeedThatFailsStopsTheSequenceBeforeTheSweep() {
        doThrow(new IllegalStateException("relation \"customers\" does not exist"))
                .when(demoData).seedDemoData();
        StartupSequence sequence = new StartupSequence(dispatcher, provides(demoData));

        assertFalse(sequence.runStartupSteps());

        verify(dispatcher, never()).sweepPending();
    }

    /**
     * And it is reported as itself: the step that failed, named, with the reason next to it.
     *
     * This is the whole of what changed. The same failure used to arrive as a bean that could not
     * be constructed, and an operator reading it had no way to tell that the missing thing was the
     * schema.
     */
    @Test
    void aSeedThatFailsSaysWhatFailedAndWhy() {
        doThrow(new IllegalStateException("relation \"customers\" does not exist"))
                .when(demoData).seedDemoData();

        String printed = whileCapturingStandardOutput(() ->
                new StartupSequence(dispatcher, provides(demoData)).runStartupSteps());

        assertTrue(printed.contains("demo data"), printed);
        assertTrue(printed.contains("relation \"customers\" does not exist"), printed);
        assertTrue(printed.contains("db/init/schema.sql"),
                "and what to do about it: " + printed);
        assertFalse(printed.contains("\tat "), "no stack trace in front of the operator: " + printed);
    }

    /** The other step is named just as plainly when it is the one that fails. */
    @Test
    void aSweepThatFailsNamesTheSweep() {
        doThrow(new IllegalStateException("connection closed")).when(dispatcher).sweepPending();

        String printed = whileCapturingStandardOutput(() ->
                new StartupSequence(dispatcher, provides(null)).runStartupSteps());

        assertTrue(printed.contains("owes the network"), printed);
        assertTrue(printed.contains("connection closed"), printed);
    }

    /**
     * Constructing the seeding bean writes nothing, which is what moved the failure out of bean
     * construction in the first place. Asserted through the mock's own record: nothing was asked
     * of it until the sequence ran.
     */
    @Test
    void nothingIsSeededUntilTheSequenceRuns() {
        new StartupSequence(dispatcher, provides(demoData));

        verify(demoData, never()).seedDemoData();
        verify(dispatcher, never()).sweepPending();
    }

    /**
     * The block goes to stdout because that is where the person who started the process is
     * looking, so that is where it has to be read back from.
     */
    private String whileCapturingStandardOutput(Runnable body) {
        PrintStream previous = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(previous);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
