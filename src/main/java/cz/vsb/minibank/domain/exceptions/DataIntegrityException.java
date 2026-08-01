package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when stored data contradicts the model: a reference that points at nothing, or
 * a row that breaks an invariant the domain enforces. Never the caller's fault, so it is
 * a server error and must not be reported as a missing or invalid request.
 */
public class DataIntegrityException extends DomainException {
    public DataIntegrityException(String msg) { super(msg); }
}
