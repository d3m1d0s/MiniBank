package cz.vsb.minibank.api;

import cz.vsb.minibank.application.AuthService;
import cz.vsb.minibank.application.BootstrapServices;
import cz.vsb.minibank.application.Pbkdf2PasswordEncoder;
import cz.vsb.minibank.application.SecurityContext;
import cz.vsb.minibank.application.SessionStore;
import cz.vsb.minibank.application.TransferApplicationService;
import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.Address;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.User;
import cz.vsb.minibank.domain.UserRole;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    private static final String TARGET_IBAN = "CZ0201000000000012345678";
    private static final int CUSTOMER_ID = 2;
    private static final int ACCOUNT_ID = 101;

    private static final String BODY_AUTH_REQUIRED =
            "{\"code\":\"AUTH_REQUIRED\",\"message\":\"You are not signed in. Please sign in and try again.\"}";
    private static final String BODY_AUTH_FAILED =
            "{\"code\":\"AUTH_FAILED\",\"message\":\"The username or password is not correct.\"}";
    private static final String BODY_FORBIDDEN =
            "{\"code\":\"FORBIDDEN\",\"message\":\"You do not have access to this operation.\"}";
    private static final String BODY_NOT_FOUND =
            "{\"code\":\"NOT_FOUND\",\"message\":\"The requested item does not exist or is not available to you.\"}";
    private static final String BODY_CONFLICT =
            "{\"code\":\"CONFLICT\",\"message\":\"This action is no longer possible because the item has already changed state.\"}";
    private static final String BODY_INVALID_OTP =
            "{\"code\":\"INVALID_OTP\",\"message\":\"The confirmation code is not valid.\"}";
    private static final String BODY_INVALID_IBAN =
            "{\"code\":\"INVALID_IBAN\",\"message\":\"The IBAN you entered is not valid.\"}";
    private static final String BODY_INSUFFICIENT_FUNDS =
            "{\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"There are not enough funds on the selected account to cover amount and fee.\"}";
    private static final String BODY_VALIDATION_ERROR =
            "{\"code\":\"VALIDATION_ERROR\",\"message\":\"The request contains invalid or missing values.\"}";
    private static final String BODY_METHOD_NOT_ALLOWED =
            "{\"code\":\"METHOD_NOT_ALLOWED\",\"message\":\"This operation is not available on this address.\"}";
    private static final String BODY_UNSUPPORTED_MEDIA_TYPE =
            "{\"code\":\"UNSUPPORTED_MEDIA_TYPE\",\"message\":\"The request body is not in a format this operation accepts.\"}";
    private static final String BODY_INTERNAL_ERROR =
            "{\"code\":\"INTERNAL_ERROR\",\"message\":\"Unexpected error occurred.\"}";

    /**
     * Two endpoints that exist only to reach the two 500 rows. Corrupting a store to
     * produce a dangling reference would test the store, not the contract.
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

    @BeforeEach
    void setUp() {
        Bootstrap infra = new Bootstrap(tempDir.resolve("data.json").toString());
        accounts = infra.accounts;
        transfers = infra.transfers;

        Customer customer = new Customer(CUSTOMER_ID, "Contract Test", "contract@example.com",
                new Address("Test Street 1", "Ostrava"));
        customer.addAccountId(ACCOUNT_ID);
        infra.customers.save(customer);
        accounts.save(new Account(ACCOUNT_ID, new IBAN(CUSTOMER_IBAN),
                Money.czk(20_000), Money.czk(5_000)));

        BootstrapServices services = new BootstrapServices(
                infra.customers, accounts, transfers, infra.alerts, infra.uowFactory);
        TransferApplicationService transferService = services.transferService;

        paymentController = new PaymentController(transferService, accounts, transfers, services.feePolicy);
        authorizationController = new AuthorizationController(transferService, accounts, transfers, services.feePolicy);
        FraudController fraudController = new FraudController(
                infra.alerts, transfers, accounts, services.fraudService, services.feePolicy);

        var encoder = new Pbkdf2PasswordEncoder();
        var users = new InMemoryUserRepository();
        byte[] salt = encoder.generateSalt();
        users.save(new User(1, "alice", encoder.hash("alice123".toCharArray(), salt), salt,
                UserRole.CUSTOMER, CUSTOMER_ID));

        sessions = new SessionStore();
        AuthController authController = new AuthController(new AuthService(users, encoder), sessions);

        api = MockMvcBuilders
                .standaloneSetup(paymentController, authorizationController, fraudController,
                        authController, new BoomController())
                .setControllerAdvice(new RestExceptionHandler())
                .build();

        guarded = MockMvcBuilders
                .standaloneSetup(paymentController, authorizationController)
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
                CUSTOMER_ID, ACCOUNT_ID, TARGET_IBAN, 6_000.0, "waiting")).getBody().transferId();
    }

    // ---------------------------------------------------------------- 401

    @Test
    void noSessionHeaderIs401AuthRequired() throws Exception {
        assertResponse(guarded, get("/api/me/accounts"), 401, BODY_AUTH_REQUIRED);
    }

    /**
     * A session id the store has never heard of - routine after a backend restart, since
     * SessionStore has no expiry - must be indistinguishable from no header at all. Both
     * renderers of this body are exercised here: the interceptor writes these two, and the
     * advice writes the one below.
     */
    @Test
    void unknownSessionIdIs401AuthRequiredWithTheSameBody() throws Exception {
        assertResponse(guarded, get("/api/me/accounts").header("X-Session-Id", "not-a-real-session"), 401, BODY_AUTH_REQUIRED);
    }

    @Test
    void noAuthenticatedUserIs401AuthRequired() throws Exception {
        SecurityContext.clear();

        assertResponse(api, get("/api/me/accounts"), 401, BODY_AUTH_REQUIRED);
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
                CUSTOMER_ID, ACCOUNT_ID, TARGET_IBAN, 100.0, "sent")).getBody().transferId();

        assertResponse(api, post("/api/transfers/" + id + "/cancel"), 409, BODY_CONFLICT);
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

    @Test
    void anAmountAboveTheBalanceIs400InsufficientFunds() throws Exception {
        assertResponse(api, post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":" + ACCOUNT_ID + ",\"targetIban\":\"" + TARGET_IBAN
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
