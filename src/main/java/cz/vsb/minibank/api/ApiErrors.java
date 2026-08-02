package cz.vsb.minibank.api;

/**
 * Every error body {@link RestExceptionHandler} can put on the wire, in one place.
 *
 * SessionAuthInterceptor writes its response from preHandle and never reaches the
 * handler advice, so the unauthenticated answer has two renderers. Both read the
 * payload from here, which is what keeps one wire fact to one body.
 *
 * Not the whole error surface of the deployed application. Requests that never reach a
 * handler - an unmatched URL, for instance - are answered by Spring Boot's own
 * BasicErrorController with a body that has no {@code code} field. application.properties
 * pins server.error.include-message and server.error.include-stacktrace to never, so those
 * bodies cannot start carrying exception text. Everything a mapped endpoint can produce
 * is here.
 */
final class ApiErrors {

    static final ApiError AUTH_REQUIRED = new ApiError(
            "AUTH_REQUIRED",
            "You are not signed in. Please sign in and try again.");

    static final ApiError AUTH_FAILED = new ApiError(
            "AUTH_FAILED",
            "The username or password is not correct.");

    static final ApiError FORBIDDEN = new ApiError(
            "FORBIDDEN",
            "You do not have access to this operation.");

    // Deliberately the same answer for an id that does not exist and for one that exists
    // but is not the caller's, so the response cannot be used to enumerate identifiers.
    static final ApiError NOT_FOUND = new ApiError(
            "NOT_FOUND",
            "The requested item does not exist or is not available to you.");

    static final ApiError CONFLICT = new ApiError(
            "CONFLICT",
            "This action is no longer possible because the item has already changed state.");

    static final ApiError VALIDATION_ERROR = new ApiError(
            "VALIDATION_ERROR",
            "The request contains invalid or missing values.");

    static final ApiError SELF_TRANSFER = new ApiError(
            "SELF_TRANSFER",
            "The destination is the account the payment is sent from. Choose a different account.");

    // Names the amounts, not the debits: the daily limit caps what a customer asks to move
    // and the fees are charged on top of it, so a day that ends exactly on the limit has
    // taken slightly more than the limit out of the account.
    static final ApiError DAILY_LIMIT_EXCEEDED = new ApiError(
            "DAILY_LIMIT_EXCEEDED",
            "This payment would take the day's payments on the selected account above its daily limit.");

    static final ApiError INVALID_OTP = new ApiError(
            "INVALID_OTP",
            "The confirmation code is not valid.");

    static final ApiError INVALID_IBAN = new ApiError(
            "INVALID_IBAN",
            "The IBAN you entered is not valid.");

    static final ApiError INSUFFICIENT_FUNDS = new ApiError(
            "INSUFFICIENT_FUNDS",
            "There are not enough funds on the selected account to cover amount and fee.");

    static final ApiError METHOD_NOT_ALLOWED = new ApiError(
            "METHOD_NOT_ALLOWED",
            "This operation is not available on this address.");

    static final ApiError UNSUPPORTED_MEDIA_TYPE = new ApiError(
            "UNSUPPORTED_MEDIA_TYPE",
            "The request body is not in a format this operation accepts.");

    static final ApiError INTERNAL_ERROR = new ApiError(
            "INTERNAL_ERROR",
            "Unexpected error occurred.");

    private ApiErrors() {}
}
