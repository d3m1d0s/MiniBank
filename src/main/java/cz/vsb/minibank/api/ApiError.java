package cz.vsb.minibank.api;

/**
 * Error payload returned by REST API endpoints.
 */
public record ApiError(String code, String message) {
}
