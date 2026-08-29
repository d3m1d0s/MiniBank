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

    // The attempt was refused before the credential was looked at, so the caller is told
    // what to do - wait - rather than that their password is wrong. Its own code for exactly
    // the reason SESSION_LIMIT_REACHED has one: both sign-in screens render this catalogue's
    // message verbatim, and AUTH_FAILED here would send somebody to change a password that is
    // correct, with no way to discover that waiting is what is required.
    //
    // It names where the attempts came from rather than the account, and says outright that
    // nothing is locked. That is the true and actionable thing: no account is ever locked by
    // LoginThrottle, the same person can sign in from elsewhere, and "your account is locked"
    // is both far more alarming and simply false. It gives an attacker nothing they could not
    // establish with one request from a second address.
    //
    // "A few minutes", not "a quarter of an hour", although the window is fifteen minutes. The
    // window is anchored at the caller's first counted attempt and never extended, so somebody
    // refused on their eleventh try has already burned most of it and the true remaining wait
    // is usually seconds. Naming the window would overstate the wait for nearly everyone who
    // sees this; SESSION_LIMIT_REACHED above sets the same precedent for the same reason.
    static final ApiError TOO_MANY_ATTEMPTS = new ApiError(
            "TOO_MANY_ATTEMPTS",
            "Too many sign-in attempts have been made from this computer or network. No account has been locked. Please wait a few minutes and try again.");

    static final ApiError FORBIDDEN = new ApiError(
            "FORBIDDEN",
            "You do not have access to this operation.");

    // Deliberately the same answer for an id that does not exist and for one that exists
    // but is not the caller's, so the response cannot be used to enumerate identifiers.
    static final ApiError NOT_FOUND = new ApiError(
            "NOT_FOUND",
            "The requested item does not exist or is not available to you.");

    // Also the body for a write a UNIQUE constraint refused, which the SQL repositories now raise
    // as a plain conflict. It fits without a word changed, and the fit is not luck: the row the
    // caller asked for is already there, put there by whoever got in first, so the item really has
    // moved on. The only violation a request can provoke is a second fraud alert on one payment,
    // and the customer's true position is that the payment is now held by the request that won.
    //
    // It names neither the constraint nor the value that collided. The driver's text names both,
    // and it stays where it has always been - off the wire, on the exception. What changes is the
    // status beside this body, not what a caller can read.
    static final ApiError CONFLICT = new ApiError(
            "CONFLICT",
            "This action is no longer possible because the item has already changed state.");

    // Another transaction changed an account this request touches between the moment this
    // request read its balance and the moment it tried to write the new one, so the write was
    // refused instead of being applied on top of a figure that is no longer true.
    //
    // Its own code rather than CONFLICT above, because the two ask for opposite things.
    // CONFLICT says the item has already changed state, which tells the customer to stop; here
    // the request was simply not first and sending it again is exactly right.
    //
    // It names no account, and that is deliberate. The guard is taken on both legs of a
    // settlement, so a customer paying an in-bank shop can be refused because a stranger paid
    // the same shop a millisecond earlier. "Another change to this account" would send them
    // looking through their own history for a change they did not make.
    //
    // It says outright that nothing was charged. A 409 on a payment otherwise reads as "it may
    // or may not have gone through", which is the single worst thing to leave a customer
    // believing about money. It is also true: the debit lived only in the JDBC transaction that
    // was rolled back before this answer was written.
    static final ApiError CONCURRENT_MODIFICATION = new ApiError(
            "CONCURRENT_MODIFICATION",
            "This payment could not be completed because another change was applied first. Nothing was charged. Please send it again.");

    // The transfers row lost the same kind of race, and it deliberately does NOT reuse the
    // message above. Two tabs on one payment: if the cancel loses to the authorization, the
    // customer has been charged - by the other tab - so "nothing was charged" is false, and
    // "please send it again" is an invitation to pay twice. The reverse order is harmless but
    // the message cannot know which way it went.
    //
    // What is true either way, and all this says: this request changed nothing, and the payment
    // is no longer in the state the caller was looking at. So it names looking again as the
    // action, not retrying. It still names no amount and no status - the status is on the
    // payment, which is where the customer is being sent.
    static final ApiError TRANSFER_CHANGED = new ApiError(
            "TRANSFER_CHANGED",
            "This payment changed while you were working on it, so nothing in this request was applied. Open the payment again to see where it stands.");

    // The fraud_alerts row lost the same kind of race, and it reuses neither message above
    // because both of those are written to a customer about their own payment. This one answers
    // an analyst, and the one thing it must not do is invite them to send the decision again:
    // the write that beat theirs may well have been the opposite verdict on the same alert, so
    // the only safe instruction is to read the alert as it now stands first.
    //
    // It names no verdict and no colleague. Which decision landed, and who recorded it, are on
    // the alert - which is where this sends them.
    static final ApiError ALERT_CHANGED = new ApiError(
            "ALERT_CHANGED",
            "This alert was decided or updated by someone else while you were working on it, so nothing in this request was applied. Open the alert again to see where it stands.");

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
    //
    // It names the customer because the ceiling does. This sentence said "on the selected
    // account above its daily limit" while the limit still lived on Account, and it outlived
    // the move: a caller reading it would take the refusal for a fact about one account and
    // send the rest from another, which is the same day and the same number. The two desks
    // stopped saying it first, in apiErrors.ts, so the wire was the last place it was wrong -
    // and the last place anybody would look, since no screen prints this text.
    static final ApiError DAILY_LIMIT_EXCEEDED = new ApiError(
            "DAILY_LIMIT_EXCEEDED",
            "This payment would take the day's payments across the customer's accounts above their daily limit.");

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
