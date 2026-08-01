package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when an authenticated user is not permitted to perform an operation.
 *
 * The reason is for the server log only. A role denial and an ownership denial answer
 * with the same body, so the response never reveals why access was refused.
 */
public class AccessDeniedException extends DomainException {

    public enum Reason { ROLE, OWNERSHIP }

    private final Reason reason;

    private AccessDeniedException(Reason reason, String msg) {
        super(msg);
        this.reason = reason;
    }

    /** The user's role does not permit this operation at all. */
    public static AccessDeniedException forRole(String detail) {
        return new AccessDeniedException(Reason.ROLE, detail);
    }

    /**
     * The user may perform this kind of operation, but not on this object.
     *
     * Prefer {@link NotFoundException} for a customer-scoped object the caller does not
     * own: answering 403 there tells the caller the id is real, which turns the endpoint
     * into an enumeration oracle. Use this factory only where the caller has already been
     * shown the object, so hiding its existence buys nothing.
     */
    public static AccessDeniedException forOwnership(String detail) {
        return new AccessDeniedException(Reason.OWNERSHIP, detail);
    }

    public Reason reason() { return reason; }
}
