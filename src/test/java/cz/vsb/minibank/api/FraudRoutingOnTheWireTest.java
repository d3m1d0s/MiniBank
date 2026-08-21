package cz.vsb.minibank.api;

import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The four fraud routes that only exist as paths, asserted on the wire rather than by calling the
 * methods behind them.
 *
 * Every other fraud test in this suite invokes the controller directly, which is exactly what
 * cannot see a mapping: a path that never matches, a path that matches the wrong handler, or a
 * body a client sends that the server refuses. Three of those are live risks here and each has a
 * case below.
 *
 * The sharpest is {@code /api/fraud/alerts/hidden}, a literal segment sitting where
 * {@code /api/fraud/alerts/{id}} also matches. It resolves to the literal, and that is a property
 * of the router rather than of this code, so it is pinned here: were it to resolve the other way
 * the request would arrive at the detail handler with "hidden" where an alert id belongs.
 */
class FraudRoutingOnTheWireTest {

    private static final IBAN PAYER_IBAN = new IBAN("CZ6508000000192000145399");
    private static final String TARGET_IBAN = "CZ2001000000000012345678";
    private static final Instant BASE = Instant.parse("2026-03-01T09:00:00Z");

    @TempDir
    Path tempDir;

    private Bootstrap infra;
    private MockMvc api;

    private int alertId;

    @BeforeEach
    void setUp() {
        infra = new Bootstrap(tempDir.resolve("data.json").toString());
        BootstrapServices services = new BootstrapServices(
                infra.customers, infra.accounts, infra.transfers, infra.alerts, infra.uowFactory);

        FraudController fraudController = new FraudController(
                infra.alerts, infra.transfers, infra.accounts, infra.customers,
                services.fraudService, services.feePolicy, infra.uowFactory);

        api = MockMvcBuilders
                .standaloneSetup(fraudController)
                .setControllerAdvice(new RestExceptionHandler())
                .build();

        SecurityContext.setCurrentUser(new User(2, "fraud", new byte[]{1}, new byte[]{2},
                UserRole.FRAUD_ANALYST, null));

        // One customer, one account, three payments, and an alert on the newest of them.
        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            int customerId = infra.customers.nextId();
            Customer c = new Customer(customerId, "Routing Probe", "routing@example.com",
                    new Address("Hlavni 1", "Ostrava"));
            infra.customers.save(c);

            int accountId = infra.accounts.nextId();
            infra.accounts.save(new Account(accountId, PAYER_IBAN,
                    Money.czk(5_000_000), Money.czk(4_000_000), null));
            c.addAccountId(accountId);
            infra.customers.save(c);

            int alertedTransferId = 0;
            for (int i = 0; i < 3; i++) {
                Transfer t = new Transfer(infra.transfers.nextId(), accountId, null,
                        TARGET_IBAN, Money.czk(12_000), BASE.plusSeconds(i * 60L));
                t.holdForReview(null);
                infra.transfers.add(t);
                alertedTransferId = t.id();
            }

            FraudAlert alert = new FraudAlert(infra.alerts.nextId(), alertedTransferId,
                    "New beneficiary + high amount");
            infra.alerts.add(alert);
            alertId = alert.id();

            scope.uow().commit();
        }
    }

    /**
     * The literal wins over the template, so the hidden queue is reachable at all.
     *
     * A 200 carrying a page is the assertion. Had the router preferred {@code /alerts/{id}}, the
     * word "hidden" would have arrived where an alert id belongs and the answer would have been a
     * failure to convert it, never a page.
     */
    @Test
    void theHiddenQueueIsItsOwnPathAndNotAnAlertCalledHidden() throws Exception {
        String body = api.perform(get("/api/fraud/alerts/hidden")
                        .param("excludeTransferStatus", "HELD_FOR_REVIEW"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"total\":1"),
                "the one alert the exclusion hides, counted: " + body);
        assertTrue(body.contains("\"page\":0") && body.contains("\"size\":25"),
                "and the paging shape the rest of this API answers with: " + body);
    }

    /** With nothing excluded there is nothing hidden, and the answer is still a page. */
    @Test
    void theHiddenQueueAnswersAnEmptyPageWhenTheFilterHidesNothing() throws Exception {
        String body = api.perform(get("/api/fraud/alerts/hidden"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"items\":[]") && body.contains("\"total\":0"), body);
    }

    /** The history beside an alert, at its own path, with the three numbers a panel needs. */
    @Test
    void theAlertHistoryPagesOnItsOwnPath() throws Exception {
        String body = api.perform(get("/api/fraud/alerts/" + alertId + "/history")
                        .param("page", "0")
                        .param("size", "2"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"page\":0"), body);
        assertTrue(body.contains("\"size\":2"), body);
        assertTrue(body.contains("\"total\":3"),
                "three payments behind the alert, of which two are on this page: " + body);
    }

    /**
     * Taking an alert and giving it back, over HTTP, with no request body on either.
     *
     * The POST carries nothing at all, which is the point: the assignee is the signed-in analyst
     * and there is no field on the wire for anybody to send another name in.
     */
    @Test
    void anAlertIsTakenWithAPostAndReleasedWithADelete() throws Exception {
        String taken = api.perform(post("/api/fraud/alerts/" + alertId + "/assignment"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(taken.contains("\"assignee\":\"fraud\""),
                "the detail comes back holding the new assignee, so the screen needs no second"
                        + " call: " + taken);
        assertEquals("fraud", infra.alerts.byId(alertId).orElseThrow().assignee());

        String released = api.perform(delete("/api/fraud/alerts/" + alertId + "/assignment"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(released.contains("\"assignee\":null"), released);
        assertNull(infra.alerts.byId(alertId).orElseThrow().assignee());
    }

    /**
     * A desk that has not been rebuilt keeps working.
     *
     * Both desks send an assignee and a list of tags on every decision, and both fields have left
     * {@link cz.vsb.minibank.api.dto.FraudDecisionRequest}. A server that refused the extra
     * properties would break the two screens the moment this deploys, so the case that matters is
     * not that the fields are gone but that sending them is harmless.
     *
     * {@code reason} is the third of them and the one that is still honoured, under a new name.
     * It always carried the analyst's own comment, so the field means exactly what it always
     * meant and is accepted as an alias for {@code comment}; only the column it lands in has
     * changed, from the sentence the rules wrote to a field of its own.
     *
     * {@code notes} is the fourth and is deliberately NOT honoured. It used to carry a whole
     * replacement blob, and the journal that replaced it appends: an old desk echoing its box back
     * on every press would file the same paragraph again as a new entry each time.
     */
    @Test
    void aDecisionCarryingTheOldFieldsIsAcceptedAndOnlyTheCommentIsHonoured() throws Exception {
        api.perform(post("/api/fraud/alerts/" + alertId + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"ANNOTATE",
                                 "reason":"still looking",
                                 "assignee":"someone.else",
                                 "tags":["high-risk"],
                                 "notes":"kept"}
                                """))
                .andReturn();

        FraudAlert stored = infra.alerts.byId(alertId).orElseThrow();
        assertEquals("still looking", stored.decisionComment(),
                "the comment field kept its old spelling as an alias, so a desk that has not been"
                        + " rebuilt still records what its analyst typed");
        assertNull(stored.assignee(),
                "and the one that left it was not: an assignee is written by its own route now,"
                        + " never by an echo travelling with a decision");
        assertTrue(stored.tags().isEmpty(), "nor can a decision write the tags column any more");
        assertTrue(infra.alerts.notesOf(alertId).isEmpty(),
                "and the old replacement blob writes no journal entry: appending it would file the"
                        + " same paragraph again on every press of every button");
    }

    /**
     * The note travels on its own field and lands in the journal under the analyst who sent it.
     *
     * The two halves of the new request are asserted apart from each other on purpose: the comment
     * belongs to the decision and is replaced by a later one, the note belongs to the case and is
     * appended. They were one field between them and one column between them, and that is what
     * made two analysts able to overwrite each other without being told.
     */
    @Test
    void aDecisionCarryingACommentAndANoteWritesBoth() throws Exception {
        api.perform(post("/api/fraud/alerts/" + alertId + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"ANNOTATE",
                                 "comment":"waiting on the card scheme",
                                 "note":"left a voicemail for the payer"}
                                """))
                .andReturn();

        FraudAlert stored = infra.alerts.byId(alertId).orElseThrow();
        assertEquals("waiting on the card scheme", stored.decisionComment());

        var journal = infra.alerts.notesOf(alertId);
        assertEquals(1, journal.size());
        assertEquals("left a voicemail for the payer", journal.get(0).text());
        assertEquals("fraud", journal.get(0).author(),
                "the author comes from the session, like decided_by, and never from the body");
    }
}
