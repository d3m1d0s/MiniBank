package cz.vsb.minibank.domain.exceptions;

/**
 * Thrown when an identifier names nothing the caller is allowed to see.
 *
 * An id that exists but belongs to another customer is thrown here too, with the same
 * message as an id that exists nowhere. The two must stay indistinguishable: a different
 * status or a different sentence for the two cases is itself the oracle that hiding the
 * object was meant to remove. Transfer and account ids are small consecutive integers.
 *
 * A dangling reference read out of our own store is NOT this exception - the caller never
 * named that id - it is {@link DataIntegrityException}.
 */
public class NotFoundException extends DomainException {
    public NotFoundException(String msg) { super(msg); }
}
