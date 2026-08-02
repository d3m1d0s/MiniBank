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

    // The credentials were fine and the caller has nothing to correct; the server has no room
    // to remember another session. A code of its own because both sign-in screens render this
    // catalogue's message verbatim, so reusing AUTH_FAILED would tell somebody whose password
    // was right that their password is wrong.
    static final ApiError SESSION_LIMIT_REACHED = new ApiError(
            "SESSION_LIMIT_REACHED",
            "Too many people are signed in right now. Please try again in a few minutes.");

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

    // Says that the bank is checking the payment and nothing else. No amount, no threshold, no
    // beneficiary, no risk score: the customer learns that it is under review and that it is
    // not lost, which is everything they can act on, and it names the one action they still
    // have.
    //
    // The message leaks nothing. The status that travels with it does: HELD_FOR_REVIEW is
    // visible to the owning customer on the payment-creation result, in the waiting list and in
    // the transfer details, so the alert threshold is discoverable by submitting and cancelling
    // payments around it. That is the price of the rule that a customer whose payment is
    // blocked must be able to see why; hiding the status would buy secrecy with a dead Confirm
    // button and no explanation, which is worse. It is written down rather than fixed.
    static final ApiError TRANSFER_UNDER_REVIEW = new ApiError(
            "TRANSFER_UNDER_REVIEW",
            "This payment is being reviewed by the bank. You will be able to confirm it once the review is finished, or you can cancel it.");

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
