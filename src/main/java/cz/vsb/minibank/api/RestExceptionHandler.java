// src/main/java/cz/vsb/minibank/api/RestExceptionHandler.java
package cz.vsb.minibank.api;

import cz.vsb.minibank.application.AppLogger;
import cz.vsb.minibank.domain.exceptions.AccessDeniedException;
import cz.vsb.minibank.domain.exceptions.AuthenticationFailedException;
import cz.vsb.minibank.domain.exceptions.ConflictException;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.exceptions.DomainException;
import cz.vsb.minibank.domain.exceptions.InsufficientFundsException;
import cz.vsb.minibank.domain.exceptions.InvalidAmountException;
import cz.vsb.minibank.domain.exceptions.InvalidIbanException;
import cz.vsb.minibank.domain.exceptions.InvalidOtpException;
import cz.vsb.minibank.domain.exceptions.NotAuthenticatedException;
import cz.vsb.minibank.domain.exceptions.NotFoundException;
import cz.vsb.minibank.domain.exceptions.SelfTransferNotAllowedException;
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

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        AppLogger.warn("api", "Access denied (" + ex.reason() + "): " + ex.getMessage());
        return error(HttpStatus.FORBIDDEN, ApiErrors.FORBIDDEN);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ApiErrors.NOT_FOUND);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex) {
        return error(HttpStatus.CONFLICT, ApiErrors.CONFLICT);
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
