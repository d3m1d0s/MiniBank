package cz.vsb.minibank.api;

import cz.vsb.minibank.application.AuthService;
import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.Pbkdf2PasswordEncoder;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.application.SessionStore;
import cz.vsb.minibank.application.TestClock;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.TooManySessionsException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.memory.InMemoryUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP error contract, asserted on the wire.
 *
 * Every other test in this suite calls controller methods directly and can only see which
 * exception came out. That is precisely what cannot catch a broken contract: a handler
 * deleted from the advice, a status changed, or an exception message leaking into a body
 * all leave the thrown type untouched. So these tests go through MockMvc and assert the
 * status code and the response body byte for byte.
 *
 * There is one case per row of the contract, and the body is compared against a literal
 * rather than against ApiErrors, so that editing a message in the catalogue fails here
 * instead of silently rewording what customers read.
 */
class HttpErrorContractTest {

    private static final String CUSTOMER_IBAN = "CZ6508000000192000145399";
    private static final String TARGET_IBAN = "CZ2001000000000012345678";
    private static final int CUSTOMER_ID = 2;
    private static final int ACCOUNT_ID = 101;

    /**
     * A second account of the same customer, opened with a balance well above its daily
     * ceiling. That is the only shape in which DAILY_LIMIT_EXCEEDED is reachable over HTTP: on
     * ACCOUNT_ID the funds check runs first, so any single amount over its 40 000 ceiling is
     * also over its 20 000 balance and answers INSUFFICIENT_FUNDS instead.
     */
    private static final int LIMITED_ACCOUNT_ID = 103;
    private static final String LIMITED_IBAN = "CZ2108000000192000145415";
    private static final double OVER_THE_LIMITED_CEILING = 12_000.0;

    /** Above RuleBasedRiskService's 10 000 alert threshold, so it is held for fraud review. */
    private static final double OVER_THE_ALERT_THRESHOLD = 12_000.0;

    /** A second customer, so the "exists but is not yours" half of 404 can be asserted. */
    private static final String VICTIM_IBAN = "CZ4308000000192000145407";
    private static final int VICTIM_CUSTOMER_ID = 3;
    private static final int VICTIM_ACCOUNT_ID = 202;

    private static final String BODY_AUTH_REQUIRED =
            "{\"code\":\"AUTH_REQUIRED\",\"message\":\"You are not signed in. Please sign in and try again.\"}";
    private static final String BODY_AUTH_FAILED =
            "{\"code\":\"AUTH_FAILED\",\"message\":\"The username or password is not correct.\"}";
    private static final String BODY_SESSION_LIMIT_REACHED =
            "{\"code\":\"SESSION_LIMIT_REACHED\",\"message\":\"Too many people are signed in right now. Please try again in a few minutes.\"}";
    private static final String BODY_FORBIDDEN =
            "{\"code\":\"FORBIDDEN\",\"message\":\"You do not have access to this operation.\"}";
    private static final String BODY_NOT_FOUND =
            "{\"code\":\"NOT_FOUND\",\"message\":\"The requested item does not exist or is not available to you.\"}";
    private static final String BODY_CONFLICT =
            "{\"code\":\"CONFLICT\",\"message\":\"This action is no longer possible because the item has already changed state.\"}";
    private static final String BODY_TRANSFER_UNDER_REVIEW =
            "{\"code\":\"TRANSFER_UNDER_REVIEW\",\"message\":\"This payment is being reviewed by the bank. You will be able to confirm it once the review is finished, or you can cancel it.\"}";
    private static final String BODY_INVALID_OTP =
            "{\"code\":\"INVALID_OTP\",\"message\":\"The confirmation code is not valid.\"}";
    private static final String BODY_INVALID_IBAN =
            "{\"code\":\"INVALID_IBAN\",\"message\":\"The IBAN you entered is not valid.\"}";
    private static final String BODY_INSUFFICIENT_FUNDS =
            "{\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"There are not enough funds on the selected account to cover amount and fee.\"}";
    private static final String BODY_SELF_TRANSFER =
            "{\"code\":\"SELF_TRANSFER\",\"message\":\"The destination is the account the payment is sent from. Choose a different account.\"}";
    private static final String BODY_DAILY_LIMIT_EXCEEDED =
            "{\"code\":\"DAILY_LIMIT_EXCEEDED\",\"message\":\"This payment would take the day's payments on the selected account above its daily limit.\"}";
    private static final String BODY_VALIDATION_ERROR =
            "{\"code\":\"VALIDATION_ERROR\",\"message\":\"The request contains invalid or missing values.\"}";
    private static final String BODY_METHOD_NOT_ALLOWED =
            "{\"code\":\"METHOD_NOT_ALLOWED\",\"message\":\"This operation is not available on this address.\"}";
    private static final String BODY_UNSUPPORTED_MEDIA_TYPE =
            "{\"code\":\"UNSUPPORTED_MEDIA_TYPE\",\"message\":\"The request body is not in a format this operation accepts.\"}";
    private static final String BODY_INTERNAL_ERROR =
            "{\"code\":\"INTERNAL_ERROR\",\"message\":\"Unexpected error occurred.\"}";

    /**
     * Endpoints that exist only to reach rows no ordinary request can. Corrupting a store to
     * produce a dangling reference would test the store, not the contract; and filling the
     * session store over HTTP would mean a thousand real logins at 120 000 PBKDF2 iterations
     * each, so the refusal is raised here instead and the wire body is what gets asserted.
     */
    @RestController
    static class BoomController {

        @GetMapping("/api/test/data-integrity")
        public String dataIntegrity() {
            throw new DataIntegrityException("Alert 7 points at missing transfer 4242");
        }

        @GetMapping("/api/test/unmapped")
        public String unmapped() {
            throw new IllegalStateException("something the contract does not know about");
        }

        @GetMapping("/api/test/session-limit")
        public String sessionLimit() {
            throw new TooManySessionsException("Session store is full at 1000");
        }
    }

    @TempDir
    Path tempDir;

    /** Controllers plus the advice: what a request sees once it is past the interceptor. */
    private MockMvc api;

    /** The same, with the session interceptor in front, for the two AUTH_REQUIRED cases. */
    private MockMvc guarded;

    private AccountRepository accounts;
    private TransferRepository transfers;
    private PaymentController paymentController;
    private AuthorizationController authorizationController;
    private SessionStore sessions;
    private InMemoryUserRepository users;
    private TestClock sessionClock;

    /** The victim's own transfers, created by the victim, as an attacker would find them. */
    private int victimWaitingTransfer;
    private int victimSentTransfer;

    @BeforeEach
    void setUp() {
        Bootstrap infra = new Bootstrap(tempDir.resolve("data.json").toString());
        accounts = infra.accounts;
        transfers = infra.transfers;

        Customer customer = new Customer(CUSTOMER_ID, "Contract Test", "contract@example.com",
                new Address("Test Street 1", "Ostrava"));
        customer.addAccountId(ACCOUNT_ID);
        customer.addAccountId(LIMITED_ACCOUNT_ID);
        infra.customers.save(customer);
        // 40 000 is the hard ceiling, matching the demo. The 6 000 payments below are meant to
        // be held for authorization, not refused, and the 500 000 in
        // anAmountAboveTheBalanceIs400InsufficientFunds is over both the balance and the
        // ceiling - it stays INSUFFICIENT_FUNDS because the funds check runs first.
        accounts.save(new Account(ACCOUNT_ID, new IBAN(CUSTOMER_IBAN),
                Money.czk(20_000), Money.czk(40_000)));
        // Balance far above the ceiling, so a payment can clear the funds check and still be
        // refused by the limit. See aPaymentOverTheDailyLimitIs400DailyLimitExceeded.
        accounts.save(new Account(LIMITED_ACCOUNT_ID, new IBAN(LIMITED_IBAN),
                Money.czk(60_000), Money.czk(10_000)));

        Customer victim = new Customer(VICTIM_CUSTOMER_ID, "Contract Victim", "victim@example.com",
                new Address("Test Street 2", "Ostrava"));
        victim.addAccountId(VICTIM_ACCOUNT_ID);
        infra.customers.save(victim);
        accounts.save(new Account(VICTIM_ACCOUNT_ID, new IBAN(VICTIM_IBAN),
                Money.czk(20_000), Money.czk(40_000)));

        BootstrapServices services = new BootstrapServices(
                infra.customers, accounts, transfers, infra.alerts, infra.uowFactory);
        TransferApplicationService transferService = services.transferService;

        // Created through the service as the victim, so they are ordinary rows rather than
        // hand-built ones, and 6 000 is above the authorization threshold while 100 is not.
        victimWaitingTransfer = transferService.submitPaymentToIban(
                VICTIM_CUSTOMER_ID, VICTIM_ACCOUNT_ID, TARGET_IBAN, 6_000.0, "victim waiting");
        victimSentTransfer = transferService.submitPaymentToIban(
                VICTIM_CUSTOMER_ID, VICTIM_ACCOUNT_ID, TARGET_IBAN, 100.0, "victim sent");

        paymentController = new PaymentController(transferService, accounts, transfers, services.feePolicy);
        authorizationController = new AuthorizationController(transferService, accounts, transfers, services.feePolicy, services.ownershipGuard);
        FraudController fraudController = new FraudController(
                infra.alerts, transfers, accounts, services.fraudService, services.feePolicy);

        var encoder = new Pbkdf2PasswordEncoder();
        users = new InMemoryUserRepository();
        byte[] salt = encoder.generateSalt();
        users.save(new User(1, "alice", encoder.hash("alice123".toCharArray(), salt), salt,
                UserRole.CUSTOMER, CUSTOMER_ID));

        // The store revalidates against the same repository the login path authenticates
        // against, and its clock is one the test moves by hand, so the expiry case below costs
        // no wall-clock time.
        sessionClock = new TestClock(Instant.parse("2026-01-01T09:00:00Z"));
        sessions = new SessionStore(users, sessionClock);
        AuthController authController = new AuthController(new AuthService(users, encoder), sessions);

        api = MockMvcBuilders
                .standaloneSetup(paymentController, authorizationController, fraudController,
                        authController, new BoomController())
                .setControllerAdvice(new RestExceptionHandler())
                .build();

        // authController is behind the interceptor here as well as in front of it above,
        // because after B11 logout is a guarded endpoint and login is the only exempt one.
        guarded = MockMvcBuilders
                .standaloneSetup(paymentController, authorizationController, authController)
                .setControllerAdvice(new RestExceptionHandler())
                .addInterceptors(new SessionAuthInterceptor(sessions))
                .build();

        signInAsCustomer();
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    private void signInAsCustomer() {
        SecurityContext.setCurrentUser(
                new User(1, "alice", new byte[]{1}, new byte[]{2}, UserRole.CUSTOMER, CUSTOMER_ID));
    }

    private void signInAsAnalyst() {
        SecurityContext.setCurrentUser(
                new User(2, "fraud", new byte[]{3}, new byte[]{4}, UserRole.FRAUD_ANALYST, null));
    }

    /** Creates a transfer above the authorization threshold, so it lands in WAITING_AUTH. */
    private int waitingTransfer() {
        return paymentController.createPayment(new cz.vsb.minibank.api.dto.NewPaymentRequest(
                ACCOUNT_ID, TARGET_IBAN, 6_000.0, "waiting")).getBody().transferId();
    }

    /**
     * Creates a transfer above the fraud-alert threshold, so it lands in HELD_FOR_REVIEW with an
     * open alert. 12 000 plus its fee is inside this account's 20 000 balance and inside its
     * 40 000 ceiling, so neither of those refusals fires first.
     */
    private int heldTransfer() {
        return paymentController.createPayment(new cz.vsb.minibank.api.dto.NewPaymentRequest(
                ACCOUNT_ID, TARGET_IBAN, OVER_THE_ALERT_THRESHOLD, "held")).getBody().transferId();
    }

    // ---------------------------------------------------------------- 401

    @Test
    void noSessionHeaderIs401AuthRequired() throws Exception {
        assertResponse(guarded, get("/api/me/accounts"), 401, BODY_AUTH_REQUIRED);
    }

    /**
     * A session id the store has never heard of - routine after a backend restart, since
     * sessions live in memory - must be indistinguishable from no header at all. Both
     * renderers of this body are exercised here: the interceptor writes these two, and the
     * advice writes the one below.
     */
    @Test
    void unknownSessionIdIs401AuthRequiredWithTheSameBody() throws Exception {
        assertResponse(guarded, get("/api/me/accounts").header("X-Session-Id", "not-a-real-session"), 401, BODY_AUTH_REQUIRED);
    }

    /**
     * A10. An expired session is the same answer again, and it has to be: telling it apart
     * from an id that was never issued would answer "is this one you have ever handed out?".
     * Before this item there was no expiry at all and a session of any age answered 200.
     */
    @Test
    void anExpiredSessionIs401AuthRequiredWithTheSameBody() throws Exception {
        String sessionId = sessions.createSession(users.byId(1).orElseThrow());
        sessionClock.advance(SessionStore.IDLE_TIMEOUT.plusSeconds(1));

        assertResponse(guarded, get("/api/me/accounts").header("X-Session-Id", sessionId), 401, BODY_AUTH_REQUIRED);
    }

    /**
     * A10's headline case, end to end. The user this session was opened for is no longer the
     * user behind that id, and the store finds that out on this request rather than never.
     * InMemoryUserRepository has no delete, so the row is replaced rather than removed - the
     * harsher half of the same check, since the id still resolves to a real user.
     */
    @Test
    void aSessionWhoseUserWasReplacedIs401AuthRequiredWithTheSameBody() throws Exception {
        String sessionId = sessions.createSession(users.byId(1).orElseThrow());
        users.save(new User(1, "mallory", new byte[]{1}, new byte[]{2}, UserRole.CUSTOMER, CUSTOMER_ID));

        assertResponse(guarded, get("/api/me/accounts").header("X-Session-Id", sessionId), 401, BODY_AUTH_REQUIRED);
    }

    /**
     * The one case in this file that is not an error, and the only end-to-end proof that
     * revalidation still lets the ordinary caller through. Without it every assertion here
     * would pass just as well if resolve answered empty for everybody and nobody could use
     * the application at all.
     */
    @Test
    void aLiveSessionIsServedThroughTheInterceptor() throws Exception {
        String sessionId = sessions.createSession(users.byId(1).orElseThrow());

        MockHttpServletResponse response = guarded
                .perform(get("/api/me/accounts").header("X-Session-Id", sessionId))
                .andReturn().getResponse();

        assertEquals(200, response.getStatus(), "body was " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(CUSTOMER_IBAN),
                "the caller's own accounts, read from the row the session points at");
    }

    @Test
    void noAuthenticatedUserIs401AuthRequired() throws Exception {
        SecurityContext.clear();

        assertResponse(api, get("/api/me/accounts"), 401, BODY_AUTH_REQUIRED);
    }

    // ------------------------------------------------- B11: logout is a guarded endpoint

    /**
     * B11. The interceptor exempted the whole /api/auth/ prefix, so this endpoint
     * authenticated nobody and terminated whatever session id it was handed.
     */
    @Test
    void logoutWithoutASessionIs401AuthRequired() throws Exception {
        assertResponse(guarded, post("/api/auth/logout"), 401, BODY_AUTH_REQUIRED);
    }

    /** And an id the store does not accept cannot be used to close one that it does. */
    @Test
    void logoutWithAnIdTheStoreRejectsLeavesLiveSessionsAlone() throws Exception {
        String live = sessions.createSession(users.byId(1).orElseThrow());

        assertResponse(guarded, post("/api/auth/logout").header("X-Session-Id", "not-a-real-session"),
                401, BODY_AUTH_REQUIRED);

        assertTrue(sessions.resolve(live).isPresent(), "a stranger must not be able to sign anyone out");
    }

    /** The endpoint still does its job for the caller it belongs to. */
    @Test
    void logoutWithTheCallersOwnSessionClosesIt() throws Exception {
        String sessionId = sessions.createSession(users.byId(1).orElseThrow());

        MockHttpServletResponse response = guarded
                .perform(post("/api/auth/logout").header("X-Session-Id", sessionId))
                .andReturn().getResponse();

        assertEquals(200, response.getStatus(), "body was " + response.getContentAsString());
        assertTrue(sessions.resolve(sessionId).isEmpty(), "the session must be gone");
    }

    /**
     * The exemption is one exact path and it still works. A 401 here would mean nobody can
     * sign in; the 400 proves the request reached the handler and the advice answered it.
     */
    @Test
    void loginIsStillReachableWithoutASession() throws Exception {
        assertResponse(guarded, post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\"}"), 400, BODY_VALIDATION_ERROR);
    }

    /**
     * The headline case of A11: this used to answer 400 INVALID_OTP, telling a customer who
     * mistyped a password that their confirmation code was wrong.
     */
    @Test
    void wrongPasswordIs401AuthFailed() throws Exception {
        assertResponse(api, post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"not-my-password\"}"), 401, BODY_AUTH_FAILED);
    }

    @Test
    void unknownUsernameIs401AuthFailedWithTheSameBody() throws Exception {
        assertResponse(api, post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"whatever\"}"), 401, BODY_AUTH_FAILED);
    }

    // ---------------------------------------------------------------- 403

    @Test
    void analystOnACustomerEndpointIs403Forbidden() throws Exception {
        signInAsAnalyst();

        assertResponse(api, get("/api/me/accounts"), 403, BODY_FORBIDDEN);
    }

    @Test
    void customerOnTheFraudQueueIs403Forbidden() throws Exception {
        assertResponse(api, get("/api/fraud/alerts"), 403, BODY_FORBIDDEN);
    }

    /**
     * N15: authorize and cancel read no identity at all before A3, so a FRAUD_ANALYST session
     * - whose customerId is null by construction - could drive them directly. The refusal is
     * a role denial rather than an ownership one, and it is raised before the path variable is
     * used for anything, so it is the same answer for every transfer id.
     */
    @Test
    void analystAuthorizingACustomerTransferIs403Forbidden() throws Exception {
        signInAsAnalyst();

        assertResponse(api, post("/api/transfers/" + victimWaitingTransfer + "/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"otp\":\"0000\"}"), 403, BODY_FORBIDDEN);

        assertVictimUntouched();
    }

    @Test
    void analystCancellingACustomerTransferIs403Forbidden() throws Exception {
        signInAsAnalyst();

        assertResponse(api, post("/api/transfers/" + victimWaitingTransfer + "/cancel"), 403, BODY_FORBIDDEN);

        assertVictimUntouched();
    }

    /** An id that does not exist must be refused the same way, so the 403 discloses nothing. */
    @Test
    void analystOnAnUnknownTransferIs403ForbiddenToo() throws Exception {
        signInAsAnalyst();

        assertResponse(api, post("/api/transfers/999999/cancel"), 403, BODY_FORBIDDEN);
    }

    // ---------------------------------------------------------------- 404

    @Test
    void anUnknownTransferIdIs404NotFound() throws Exception {
        assertResponse(api, get("/api/transfers/999999"), 404, BODY_NOT_FOUND);
    }

    @Test
    void anUnknownAlertIdIs404NotFound() throws Exception {
        signInAsAnalyst();

        assertResponse(api, get("/api/fraud/alerts/999999"), 404, BODY_NOT_FOUND);
    }

    @Test
    void anUnknownSourceAccountOnAPaymentIs404NotFound() throws Exception {
        assertResponse(api, post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":999999,\"targetIban\":\"" + TARGET_IBAN
                                + "\",\"amountCzk\":100.0,\"message\":\"x\"}"), 404, BODY_NOT_FOUND);
    }

    /**
     * A3, and the half that matters. An account that exists but belongs to somebody else has
     * to produce the same status and the same body bytes as one that exists nowhere - the
     * assertion above and this one share the BODY_NOT_FOUND literal, so a refusal that grew a
     * distinguishing word fails here. Account ids are small consecutive integers, and this is
     * what stops them being enumerated by their answers.
     */
    @Test
    void anotherCustomersSourceAccountIsTheSame404AsAnUnknownOne() throws Exception {
        assertResponse(api, post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":" + VICTIM_ACCOUNT_ID + ",\"targetIban\":\"" + TARGET_IBAN
                                + "\",\"amountCzk\":900.0,\"message\":\"x\"}"), 404, BODY_NOT_FOUND);

        assertVictimUntouched();
    }

    @Test
    void authorizingAnotherCustomersTransferIs404NotFound() throws Exception {
        assertResponse(api, post("/api/transfers/" + victimWaitingTransfer + "/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"otp\":\"0000\"}"), 404, BODY_NOT_FOUND);

        assertVictimUntouched();
    }

    @Test
    void cancellingAnotherCustomersTransferIs404NotFound() throws Exception {
        assertResponse(api, post("/api/transfers/" + victimWaitingTransfer + "/cancel"), 404, BODY_NOT_FOUND);

        assertVictimUntouched();
    }

    /**
     * The ordering, asserted where it is visible to a client: a stranger's SENT transfer must
     * answer 404, not the 409 that cancellingASentTransferIs409Conflict pins for its owner.
     * A 409 a non-owner can reach proves the id is real.
     */
    @Test
    void cancellingAnotherCustomersSentTransferIs404AndNotAConflict() throws Exception {
        assertResponse(api, post("/api/transfers/" + victimSentTransfer + "/cancel"), 404, BODY_NOT_FOUND);

        assertVictimUntouched();
    }

    /**
     * The same for the authorize path, where reaching the status guard would also have let a
     * stranger spend the victim's OTP attempts and get 400 INVALID_OTP instead.
     */
    @Test
    void authorizingAnotherCustomersSentTransferIs404AndNotAConflict() throws Exception {
        assertResponse(api, post("/api/transfers/" + victimSentTransfer + "/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"otp\":\"9999\"}"), 404, BODY_NOT_FOUND);

        assertVictimUntouched();
    }

    // ---------------------------------------------------------------- 409

    @Test
    void authorizingATransferThatIsNotWaitingIs409Conflict() throws Exception {
        int id = waitingTransfer();
        authorizationController.cancel(id);

        assertResponse(api, post("/api/transfers/" + id + "/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"otp\":\"0000\"}"), 409, BODY_CONFLICT);
    }

    @Test
    void cancellingASentTransferIs409Conflict() throws Exception {
        // Below the authorization threshold, so it is sent immediately.
        int id = paymentController.createPayment(new cz.vsb.minibank.api.dto.NewPaymentRequest(
                ACCOUNT_ID, TARGET_IBAN, 100.0, "sent")).getBody().transferId();

        assertResponse(api, post("/api/transfers/" + id + "/cancel"), 409, BODY_CONFLICT);
    }

    /**
     * Its own row in the contract rather than the generic conflict. Told only that the item
     * "has already changed state", a customer whose payment is under review has no way to see
     * that the bank is looking at it or that they can still cancel it - the same reason
     * SELF_TRANSFER and DAILY_LIMIT_EXCEEDED have codes of their own.
     */
    @Test
    void authorizingATransferHeldForFraudReviewIs409TransferUnderReview() throws Exception {
        assertResponse(api, post("/api/transfers/" + heldTransfer() + "/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"otp\":\"0000\"}"), 409, BODY_TRANSFER_UNDER_REVIEW);
    }

    /**
     * And the body still names nothing about the rule that raised the alert: no amount, no
     * threshold, no beneficiary, no risk score. The transfer id is in the exception message and
     * the exception message stays in the log.
     */
    @Test
    void theUnderReviewBodyLeaksNothingAboutTheFraudRules() throws Exception {
        String body = api.perform(post("/api/transfers/" + heldTransfer() + "/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"otp\":\"0000\"}"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("10000") || body.contains("10 000"),
                "the alert threshold must not be in the body: " + body);
        assertFalse(body.contains(String.valueOf((int) OVER_THE_ALERT_THRESHOLD)),
                "the amount must not be in the body: " + body);
        assertFalse(body.contains(TARGET_IBAN), "the beneficiary must not be in the body: " + body);
        assertFalse(body.toLowerCase().contains("fraud"),
                "the word fraud must not be in the body: " + body);
        assertFalse(body.contains("HELD_FOR_REVIEW"),
                "internal state wording must not come back: " + body);
    }

    // ---------------------------------------------------------------- 400

    @Test
    void aWrongOneTimePasswordIs400InvalidOtp() throws Exception {
        int id = waitingTransfer();

        assertResponse(api, post("/api/transfers/" + id + "/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"otp\":\"9999\"}"), 400, BODY_INVALID_OTP);
    }

    @Test
    void aMalformedIbanIs400InvalidIban() throws Exception {
        assertResponse(api, post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":" + ACCOUNT_ID
                                + ",\"targetIban\":\"XX12\",\"amountCzk\":100.0,\"message\":\"x\"}"), 400, BODY_INVALID_IBAN);
    }

    /**
     * Its own code rather than the generic one: told only that something was invalid, a
     * customer has no way to see that the destination they picked was their own account.
     */
    @Test
    void payingTheSourceAccountsOwnIbanIs400SelfTransfer() throws Exception {
        assertResponse(api, post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":" + ACCOUNT_ID + ",\"targetIban\":\"" + CUSTOMER_IBAN
                                + "\",\"amountCzk\":100.0,\"message\":\"x\"}"), 400, BODY_SELF_TRANSFER);
    }

    @Test
    void anAmountAboveTheBalanceIs400InsufficientFunds() throws Exception {
        assertResponse(api, post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":" + ACCOUNT_ID + ",\"targetIban\":\"" + TARGET_IBAN
                                + "\",\"amountCzk\":500000.0,\"message\":\"x\"}"), 400, BODY_INSUFFICIENT_FUNDS);
    }

    /**
     * A9, on the wire. Its own code rather than the generic one, for the reason A5 established:
     * told only that the request was invalid, a customer has no way to see that it was the
     * day's running total that stopped them.
     */
    @Test
    void aPaymentOverTheDailyLimitIs400DailyLimitExceeded() throws Exception {
        assertResponse(api, post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":" + LIMITED_ACCOUNT_ID + ",\"targetIban\":\"" + TARGET_IBAN
                                + "\",\"amountCzk\":" + OVER_THE_LIMITED_CEILING
                                + ",\"message\":\"x\"}"), 400, BODY_DAILY_LIMIT_EXCEEDED);

        assertEquals(Money.czk(60_000), accounts.byId(LIMITED_ACCOUNT_ID).orElseThrow().balance(),
                "A refused payment must not have debited anything");
    }

    /**
     * The ordering the two checks resolve in, pinned where a client can see it: 500 000 is over
     * the balance and over the ceiling at once, and the answer is the one the customer can act
     * on. Moving the limit check above canDebit in routeTransferCreation turns this red.
     */
    @Test
    void anAmountOverBothTheBalanceAndTheLimitIsAnsweredAsInsufficientFunds() throws Exception {
        assertResponse(api, post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":" + LIMITED_ACCOUNT_ID + ",\"targetIban\":\"" + TARGET_IBAN
                                + "\",\"amountCzk\":500000.0,\"message\":\"x\"}"), 400, BODY_INSUFFICIENT_FUNDS);
    }

    @Test
    void aLoginWithoutAPasswordIs400ValidationError() throws Exception {
        assertResponse(api, post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\"}"), 400, BODY_VALIDATION_ERROR);
    }

    @Test
    void anAuthorizeWithoutAnOtpIs400ValidationError() throws Exception {
        int id = waitingTransfer();

        assertResponse(api, post("/api/transfers/" + id + "/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"), 400, BODY_VALIDATION_ERROR);
    }

    @Test
    void aNonPositiveAmountIs400ValidationError() throws Exception {
        assertResponse(api, post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":" + ACCOUNT_ID + ",\"targetIban\":\"" + TARGET_IBAN
                                + "\",\"amountCzk\":0.0,\"message\":\"x\"}"), 400, BODY_VALIDATION_ERROR);
    }

    @Test
    void anUnknownAlertStateFilterIs400ValidationError() throws Exception {
        signInAsAnalyst();

        assertResponse(api, get("/api/fraud/alerts").param("state", "BOGUS"), 400, BODY_VALIDATION_ERROR);
    }

    @Test
    void anUnparseableDateFilterIs400ValidationError() throws Exception {
        signInAsAnalyst();

        assertResponse(api, get("/api/fraud/alerts").param("createdFrom", "yesterday"), 400, BODY_VALIDATION_ERROR);
    }

    /** HttpMessageNotReadableException: unchecked, so without its handler this was a 500. */
    @Test
    void anUnparseableJsonBodyIs400ValidationError() throws Exception {
        assertResponse(api, post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"), 400, BODY_VALIDATION_ERROR);
    }

    /** MethodArgumentTypeMismatchException, same story. */
    @Test
    void aNonNumericPathVariableIs400ValidationError() throws Exception {
        assertResponse(api, get("/api/transfers/abc"), 400, BODY_VALIDATION_ERROR);
    }

    // ---------------------------------------------------------------- 405 and 415

    /**
     * HttpRequestMethodNotSupportedException is a checked ServletException, so the
     * RuntimeException catch-all never sees it. Without its own handler the client gets
     * Spring Boot's error body, which carries no code field at all.
     */
    @Test
    void theWrongVerbIs405MethodNotAllowed() throws Exception {
        assertResponse(api, get("/api/transfers/1/authorize"), 405, BODY_METHOD_NOT_ALLOWED);
    }

    @Test
    void aNonJsonBodyIs415UnsupportedMediaType() throws Exception {
        assertResponse(api, post("/api/payments")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("sourceAccountId=101"), 415, BODY_UNSUPPORTED_MEDIA_TYPE);
    }

    // ---------------------------------------------------------------- 503

    /**
     * A10's global cap, on the wire. Its own code and its own status because none of the
     * neighbours would be true: the password was right, so AUTH_FAILED would send the caller
     * to change something that is not wrong, and nothing is broken, so a 500 would say the
     * request cannot succeed when the same one succeeds a few minutes later.
     */
    @Test
    void aRefusedSessionIs503SessionLimitReached() throws Exception {
        assertResponse(api, get("/api/test/session-limit"), 503, BODY_SESSION_LIMIT_REACHED);
    }

    // ---------------------------------------------------------------- 500

    /**
     * Inconsistent stored data is logged as our fault and reported as nothing at all. The
     * body must be identical to any other 500: the client must not learn the fault is in
     * our data rather than in its request.
     */
    @Test
    void inconsistentStoredDataIs500InternalErrorWithNoDetail() throws Exception {
        assertResponse(api, get("/api/test/data-integrity"), 500, BODY_INTERNAL_ERROR);
    }

    @Test
    void anUnmappedFailureIs500InternalErrorWithNoDetail() throws Exception {
        assertResponse(api, get("/api/test/unmapped"), 500, BODY_INTERNAL_ERROR);
    }

    // ---------------------------------------------------------------- no leaks

    /**
     * The property that ties the whole contract together. Three responses used to echo
     * ex.getMessage(): DOMAIN_ERROR, INVALID_STATE, and the interceptor's two 401 texts.
     * Between them they put an account balance, an aggregate's internal wording and
     * internal transfer and account ids in front of the client.
     */
    @Test
    void noErrorBodyEchoesAnExceptionMessage() throws Exception {
        int waiting = waitingTransfer();
        authorizationController.cancel(waiting);

        String[] bodies = {
                api.perform(get("/api/transfers/999999")).andReturn().getResponse().getContentAsString(),
                api.perform(post("/api/transfers/" + waiting + "/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"otp\":\"0000\"}")).andReturn().getResponse().getContentAsString(),
                api.perform(get("/api/test/data-integrity")).andReturn().getResponse().getContentAsString(),
                api.perform(get("/api/test/unmapped")).andReturn().getResponse().getContentAsString(),
                api.perform(get("/api/test/session-limit")).andReturn().getResponse().getContentAsString(),
                guarded.perform(get("/api/me/accounts").header("X-Session-Id", "ghost"))
                        .andReturn().getResponse().getContentAsString(),
        };

        for (String body : bodies) {
            assertFalse(body.contains("999999"),
                    "An id the request did not carry must not come back: " + body);
            assertFalse(body.contains("4242"),
                    "An internal id must not come back: " + body);
            assertFalse(body.contains("ghost"),
                    "A session id must not be echoed: " + body);
            assertFalse(body.toLowerCase().contains("exception"),
                    "An exception class name must not come back: " + body);
            assertFalse(body.contains("DECLINED"),
                    "Internal state wording must not come back: " + body);
        }
    }

    /**
     * A refused request must leave the victim exactly as it found them. Asserting only the
     * status would pass just as well for a refusal thrown after the money had already moved.
     */
    private void assertVictimUntouched() {
        assertEquals(Money.czk(19_900), accounts.byId(VICTIM_ACCOUNT_ID).orElseThrow().balance(),
                "The victim's balance must not move; only their own 100.00 payment settled");

        var waiting = transfers.byId(victimWaitingTransfer).orElseThrow();
        assertEquals(TransferStatus.WAITING_AUTH, waiting.status());
        assertEquals(0, waiting.authAttempts(),
                "A stranger must not be able to spend the victim's OTP attempts");
        assertNull(waiting.declineReason(),
                "A stranger must not be able to write the victim's audit reason");

        assertEquals(TransferStatus.SENT, transfers.byId(victimSentTransfer).orElseThrow().status());
    }

    /**
     * Asserts the status and the exact response bytes.
     *
     * Deliberately not MockMvcResultMatchers.content().string(...): that overload is typed
     * against a hamcrest Matcher, and hamcrest is not on this project's test classpath.
     * Comparing the whole body as a string is the stronger assertion anyway - a body that
     * grew an extra field would pass a field-by-field check and fail here.
     */
    private static void assertResponse(MockMvc mvc,
                                       RequestBuilder request,
                                       int expectedStatus,
                                       String expectedBody) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();

        assertEquals(expectedStatus, response.getStatus(),
                "Wrong status; body was " + response.getContentAsString());
        assertEquals(expectedBody, response.getContentAsString());
    }
}
