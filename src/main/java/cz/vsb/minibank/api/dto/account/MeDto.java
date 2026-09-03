package cz.vsb.minibank.api.dto.account;

/**
 * Who the caller is signed in as.
 *
 * The login response already carries the username, the role and the customer id, and this record
 * repeats them on purpose: those three arrive once, at the moment a session is opened, and a screen
 * that reloads has either kept them somewhere or lost them. A route that answers "who am I" from
 * the session is the only way to get them back without asking for a password again.
 *
 * WHAT IS NEW HERE is everything below the id. The console has been able to greet a customer by
 * name since it was written - it holds the {@code Customer} aggregate directly - and the browser
 * has had no route that returns one at all, so the legacy surface was strictly more capable than
 * the product. Both front ends had to print the login name in the place a person's name belongs.
 *
 * @param username   the login name, always present
 * @param role       the role name, always present. A plain string like every other enumeration on
 *                   this wire, so an unrecognised value renders rather than failing to parse
 * @param customerId the customer this login acts for, or null for a user that is not a customer.
 *                   An analyst has none, and that is not a fault
 * @param name       the customer's own name, or null when the caller is not a customer. Null and
 *                   not the username: a fallback chosen here would be a fallback every screen then
 *                   has to unpick, and the screen is where the choice between a name and a login
 *                   belongs
 * @param email      the customer's email, or null on the same terms
 * @param address    the customer's postal address, or null when the caller is not a customer or
 *                   the customer row carries none
 */
public record MeDto(
        String username,
        String role,
        Integer customerId,
        String name,
        String email,
        AddressDto address
) {

    /**
     * A postal address on the wire.
     *
     * Nested rather than flattened into two more fields above, because street and city are one
     * value: a client that has an address has both halves or neither, and the null that says "this
     * caller has no address" has somewhere to live.
     */
    public record AddressDto(String street, String city) {
    }
}
