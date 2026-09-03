package cz.vsb.minibank.api.dto.account;

/**
 * A saved payee as the browser is allowed to see one: who is being paid, and the account the
 * money goes to.
 *
 * THREE COMPONENTS, AND NO FOURTH. The domain object carries a trusted flag as well, and it is
 * left off this wire on purpose rather than by oversight, which is why this paragraph exists: the
 * next reader will open Beneficiary, find the field, and assume somebody forgot it. The flag is
 * what decides whether a payment to this payee is held for review, so a customer who can see it
 * has been shown which payee to choose in order to escape the check. It is not sent as false, it
 * is not sent under a gentler name, and it is not sent as a hint the client is told to ignore.
 *
 * THE ORDER IS PART OF THE SAME DECISION. This list is sorted by name, case insensitively, on the
 * server. Sorting trusted payees to the top would publish the flag through the sequence without
 * ever naming it, and one order settled server side also means both platforms show the same list
 * in the same order.
 *
 * The IBAN is sent as the account holds it, unspaced. Grouping it into fours is the screen's job
 * and there is one function for it already; a DTO that pre-formats a value is a second place a
 * format is decided from.
 */
public record BeneficiaryDto(
        int id,
        String name,
        String iban
) {
}
