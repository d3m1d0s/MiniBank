// src/main/java/cz/vsb/minibank/api/RestExceptionHandler.java
package cz.vsb.minibank.api.web;

import cz.vsb.minibank.application.audit.AppLogger;
import cz.vsb.minibank.domain.exceptions.AccessDeniedException;
import cz.vsb.minibank.domain.exceptions.AuthenticationFailedException;
import cz.vsb.minibank.domain.exceptions.ConflictException;
import cz.vsb.minibank.domain.exceptions.DailyLimitExceededException;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.DomainException;
import cz.vsb.minibank.domain.exceptions.FraudAlertChangedException;
import cz.vsb.minibank.domain.exceptions.InsufficientFundsException;
import cz.vsb.minibank.domain.exceptions.InvalidAmountException;
import cz.vsb.minibank.domain.exceptions.InvalidIbanException;
import cz.vsb.minibank.domain.exceptions.InvalidOtpException;
import cz.vsb.minibank.domain.exceptions.NotAuthenticatedException;
import cz.vsb.minibank.domain.exceptions.NotFoundException;
import cz.vsb.minibank.domain.exceptions.OptimisticLockException;
import cz.vsb.minibank.domain.exceptions.SelfTransferNotAllowedException;
import cz.vsb.minibank.domain.exceptions.TooManyLoginAttemptsException;
import cz.vsb.minibank.domain.exceptions.TooManySessionsException;
import cz.vsb.minibank.domain.exceptions.TransferChangedException;
import cz.vsb.minibank.domain.exceptions.TransferUnderReviewException;
import cz.vsb.minibank.domain.exceptions.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Global REST exception handler that maps domain and technical errors to HTTP responses.
 *
 * Every answer comes from the {@link ApiErrors} catalogue. Exception messages are written
 * for the server log and are never put on the wire, so internal ids and domain wording
 * cannot leak. All handlers live in this one advice: within a single advice Spring resolves
 * by ExceptionDepthComparator, so a specific handler always beats handleRuntime. A second
 * advice would be ordered only by @Order and could shadow these.
 */
@ControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(NotAuthenticatedException.class)
    public ResponseEntity<ApiError> handleNotAuthenticated(NotAuthenticatedException ex) {
        return error(HttpStatus.UNAUTHORIZED, ApiErrors.AUTH_REQUIRED);
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ApiError> handleAuthenticationFailed(AuthenticationFailedException ex) {
        AppLogger.warn("api", "Sign-in refused: " + ex.getMessage());
        return error(HttpStatus.UNAUTHORIZED, ApiErrors.AUTH_FAILED);
    }

    /**
     * The store refused to open another session. 503 rather than 500: nothing is broken, and
     * the same request succeeds later as sessions expire. Not a 401 either - answering a
     * correct password with an authentication failure would send the caller to fix something
     * that is not wrong.
     */
    @ExceptionHandler(TooManySessionsException.class)
    public ResponseEntity<ApiError> handleTooManySessions(TooManySessionsException ex) {
        AppLogger.warn("api", "Sign-in refused: " + ex.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, ApiErrors.SESSION_LIMIT_REACHED);
    }

    /**
     * The caller has spent its recent sign-in allowance. 429 rather than 401, because
     * nothing about the credential was checked: calling it an authentication failure would be
     * a claim about something the server never looked at, and would hide from an honest user
     * the one thing they can act on. Added for the same reason the 503 above was - the caller
     * has nothing to correct and the same request works later.
     *
     * Not 503 either: that says the server is at fault and everyone is affected, where here
     * one caller is limited and the rest of the bank is fine.
     *
     * The only handler here that deliberately writes nothing to the log. It fires once per
     * refused attempt, and an attempt is one unauthenticated POST, so a line each would let
     * any caller drive the unrotated log file at request rate - what SessionAuthInterceptor
     * declines to do for its two 401 paths. The attempts this replaces were each writing one
     * through handleAuthenticationFailed, so the net effect is a quieter log, at the price of
     * the throttle firing leaving no trace at all.
     */
    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<ApiError> handleTooManyLoginAttempts(TooManyLoginAttemptsException ex) {
        return error(HttpStatus.TOO_MANY_REQUESTS, ApiErrors.TOO_MANY_ATTEMPTS);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        AppLogger.warn("api", "Access denied (" + ex.reason() + "): " + ex.getMessage());
        return error(HttpStatus.FORBIDDEN, ApiErrors.FORBIDDEN);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ApiErrors.NOT_FOUND);
    }

    /**
     * Also where a write the store itself refused now arrives. A UNIQUE constraint violation used
     * to leave the SQL repositories as a bare RuntimeException and be answered by handleRuntime
     * below as 500 INTERNAL_ERROR, which claims the bank is broken over a row the database
     * declined to duplicate: the transaction rolled back whole and nothing was half written, so
     * 409 is the honest status and this is the family that already carries it.
     *
     * No code of its own, because the answer would be the same one. The exception message names
     * the aggregate and its id and reaches no response - like every handler here, this one builds
     * its body from the catalogue and echoes nothing.
     *
     * It writes no log line, as it never has for a conflict, and that is the one thing given up
     * here: the 500 this replaces was logged at error with the driver's stack trace, so a race
     * that files a row twice now leaves its trace in the database's own log rather than in this
     * application's. The alternative is a line for every ordinary domain conflict as well, which
     * this handler has always declined to write. The refusal still carries the driver's exception
     * as its cause, so anywhere the throwable itself is logged - ConsoleMenu does - the constraint
     * is still named.
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex) {
        return error(HttpStatus.CONFLICT, ApiErrors.CONFLICT);
    }

    /**
     * A subtype of ConflictException, so Spring picks this more specific handler and the
     * customer is told their payment is under review instead of only that it changed state.
     * Still a 409: a blocked authorization is a state conflict, not a bad request.
     *
     * The exception message carries the transfer id and reaches the server log only.
     */
    @ExceptionHandler(TransferUnderReviewException.class)
    public ResponseEntity<ApiError> handleTransferUnderReview(TransferUnderReviewException ex) {
        return error(HttpStatus.CONFLICT, ApiErrors.TRANSFER_UNDER_REVIEW);
    }

    /**
     * Also a subtype of ConflictException, and picked over the generic handler by the same
     * ExceptionDepthComparator rule as the one above.
     *
     * Still a 409: the write really was refused because the row had moved on. What it must not
     * be is a 500 - nothing is broken, nothing was charged, and resubmitting works - which is
     * exactly what a plain RuntimeException out of the repository would have produced.
     *
     * The exception message carries the account id and the stale version and reaches the log
     * only. Logged at warn rather than not at all, because a burst of these is the signal that
     * one account is a contention point.
     */
    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockException ex) {
        AppLogger.warn("api", "Refused a stale account write: " + ex.getMessage());
        return error(HttpStatus.CONFLICT, ApiErrors.CONCURRENT_MODIFICATION);
    }

    /**
     * The transfers row lost a race.
     *
     * Declared separately from its own supertype, which Spring resolves by picking the most
     * specific handler, because the two answers differ: this one must not promise that nothing
     * was charged. See {@link ApiErrors#TRANSFER_CHANGED}.
     */
    @ExceptionHandler(TransferChangedException.class)
    public ResponseEntity<ApiError> handleTransferChanged(TransferChangedException ex) {
        AppLogger.warn("api", "Refused a stale transfer write: " + ex.getMessage());
        return error(HttpStatus.CONFLICT, ApiErrors.TRANSFER_CHANGED);
    }

    /**
     * The fraud_alerts row lost a race.
     *
     * A third sibling under the same supertype, declared separately for the reason the transfer
     * one is: the answers differ. This is the only one of the three addressed to an analyst
     * rather than to a customer, and the action it names is to reopen the alert, because the
     * write that beat this one may have been the opposite verdict. See {@link ApiErrors#ALERT_CHANGED}.
     */
    @ExceptionHandler(FraudAlertChangedException.class)
    public ResponseEntity<ApiError> handleFraudAlertChanged(FraudAlertChangedException ex) {
        AppLogger.warn("api", "Refused a stale fraud alert write: " + ex.getMessage());
        return error(HttpStatus.CONFLICT, ApiErrors.ALERT_CHANGED);
    }

    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<ApiError> handleInvalidOtp(InvalidOtpException ex) {
        return error(HttpStatus.BAD_REQUEST, ApiErrors.INVALID_OTP);
    }

    @ExceptionHandler(InvalidIbanException.class)
    public ResponseEntity<ApiError> handleInvalidIban(InvalidIbanException ex) {
        return error(HttpStatus.BAD_REQUEST, ApiErrors.INVALID_IBAN);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiError> handleInsufficientFunds(InsufficientFundsException ex) {
        return error(HttpStatus.BAD_REQUEST, ApiErrors.INSUFFICIENT_FUNDS);
    }

    /**
     * A subtype of ValidationException, so Spring picks this more specific handler and the
     * caller is told which value is wrong instead of only that something is.
     */
    @ExceptionHandler(SelfTransferNotAllowedException.class)
    public ResponseEntity<ApiError> handleSelfTransfer(SelfTransferNotAllowedException ex) {
        return error(HttpStatus.BAD_REQUEST, ApiErrors.SELF_TRANSFER);
    }

    /**
     * The same arrangement, for the same reason: told only that the request was invalid, a
     * customer has no way to see that it was the day's running total that stopped them.
     *
     * The exception message carries the limit, the running total and the requested amount.
     * It reaches the server log only - every answer is built from the catalogue and no
     * handler echoes ex.getMessage().
     */
    @ExceptionHandler(DailyLimitExceededException.class)
    public ResponseEntity<ApiError> handleDailyLimitExceeded(DailyLimitExceededException ex) {
        return error(HttpStatus.BAD_REQUEST, ApiErrors.DAILY_LIMIT_EXCEEDED);
    }

    @ExceptionHandler({ValidationException.class, InvalidAmountException.class})
    public ResponseEntity<ApiError> handleValidation(DomainException ex) {
        return error(HttpStatus.BAD_REQUEST, ApiErrors.VALIDATION_ERROR);
    }

    /**
     * Spring's own binding failures. Both are unchecked, so without these two the catch-all
     * below matches them and downgrades the framework's 400 to a 500.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleUnreadableRequest(Exception ex) {
        return error(HttpStatus.BAD_REQUEST, ApiErrors.VALIDATION_ERROR);
    }

    /**
     * Right URL, wrong verb. This one is a checked ServletException, so it never reaches
     * handleRuntime; without this handler it falls to DefaultHandlerExceptionResolver and
     * the client gets Spring Boot's error body, which carries no {@code code} field.
     * A 405 answered as 400 would be a new lie, so it keeps its own status and code.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, ApiErrors.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ApiErrors.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler(DataIntegrityException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityException ex) {
        AppLogger.error("api", "Stored data is inconsistent with the domain model", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrors.INTERNAL_ERROR);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> handleRuntime(RuntimeException ex) {
        AppLogger.error("api", "Unhandled error while serving a request", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrors.INTERNAL_ERROR);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, ApiError body) {
        return ResponseEntity.status(status).body(body);
    }
}
