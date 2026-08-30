package cz.vsb.minibank.api.dto;

/**
 * Request payload for creating a new outgoing payment.
 *
 * There is deliberately no customerId here. The paying customer comes from the session and
 * from nowhere else; a field a caller can set would be one line away from being trusted.
 *
 * TWO WAYS TO NAME A DESTINATION, AND EXACTLY ONE PER REQUEST. beneficiaryId points at a payee
 * in the caller's own address book and targetIban is the account number typed by hand. The
 * beneficiary route existed in the application service from the start and had no way in from
 * HTTP, so every payment the browser made was submitted as if the payee were unknown - which
 * left the trusted branch of the risk rules unreachable from the one client that ships.
 *
 * beneficiaryId is boxed so that absent is null rather than 0, and 0 is not a spare value here:
 * it is a perfectly ordinary identifier to ask for and would be resolved as one. Naming both is
 * refused rather than resolved by preferring one - an instruction that gives two destinations
 * for one payment is a caller that has lost track of which it meant, and guessing moves money.
 */
public record NewPaymentRequest(
        int sourceAccountId,
        String targetIban,
        Integer beneficiaryId,
        double amountCzk,
        String message
) {
}
