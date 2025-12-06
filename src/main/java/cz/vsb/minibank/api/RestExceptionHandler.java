// src/main/java/cz/vsb/minibank/api/RestExceptionHandler.java
package cz.vsb.minibank.api;

import cz.vsb.minibank.domain.exceptions.AuthorizationFailedException;
import cz.vsb.minibank.domain.exceptions.DailyLimitExceededException;
import cz.vsb.minibank.domain.exceptions.DomainException;
import cz.vsb.minibank.domain.exceptions.InsufficientFundsException;
import cz.vsb.minibank.domain.exceptions.InvalidIbanException;
import cz.vsb.minibank.domain.exceptions.InvalidStateTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Global REST exception handler that maps domain and technical errors to HTTP responses.
 */
@ControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(InvalidIbanException.class)
    public ResponseEntity<ApiError> handleInvalidIban(InvalidIbanException ex) {
        return error(HttpStatus.BAD_REQUEST,
                "INVALID_IBAN",
                "The IBAN you entered is not valid.");
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiError> handleInsufficientFunds(InsufficientFundsException ex) {
        return error(HttpStatus.BAD_REQUEST,
                "INSUFFICIENT_FUNDS",
                "There are not enough funds on the selected account to cover amount and fee.");
    }

    @ExceptionHandler(DailyLimitExceededException.class)
    public ResponseEntity<ApiError> handleDailyLimit(DailyLimitExceededException ex) {
        return error(HttpStatus.BAD_REQUEST,
                "DAILY_LIMIT_EXCEEDED",
                "Daily limit for this account has been exceeded.");
    }

    @ExceptionHandler(AuthorizationFailedException.class)
    public ResponseEntity<ApiError> handleAuthorizationFailed(AuthorizationFailedException ex) {
        return error(HttpStatus.BAD_REQUEST,
                "INVALID_OTP",
                "The confirmation code is not valid.");
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ApiError> handleInvalidState(InvalidStateTransitionException ex) {
        return error(HttpStatus.CONFLICT,
                "INVALID_STATE",
                ex.getMessage());
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex) {
        return error(HttpStatus.BAD_REQUEST,
                "DOMAIN_ERROR",
                ex.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> handleRuntime(RuntimeException ex) {
        ex.printStackTrace(); // alternatively use AppLogger.error("REST runtime error", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Unexpected error occurred.");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}
