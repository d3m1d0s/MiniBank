package cz.vsb.minibank.api.dto.payment;
import cz.vsb.minibank.api.dto.common.MoneyDto;

/**
 * What a payment would cost and what it would ask of the customer, before any money moves.
 *
 * The tariff is a step function of the whole amount, so one heller past a boundary changes the fee
 * by ten crowns and again by twenty-five at the next one. Until this route existed the only way to
 * learn the number was to send the payment, and the receipt is a poor place to discover that
 * rounding an amount up cost more than the difference.
 *
 * IT IS QUOTED AND NOT RECOMPUTED, which is the whole reason this is a route rather than a table in
 * the client. A copy of the tariff in the browser is a copy that drifts: it would have to be
 * changed in three places on the day a boundary moves, and the two that were forgotten would go on
 * quoting last year's price beside this year's charge.
 *
 * @param amount                what was asked about, echoed back so a client that fired several
 *                              quotes while the customer typed can tell which answer belongs to
 *                              what is in the box now
 * @param fee                   what this bank would charge on top of it
 * @param total                 amount plus fee, the figure that leaves the account. Sent rather
 *                              than left to be added, for the reason the fee is sent at all: two
 *                              decimal additions written on two clients is two chances to write one
 *                              of them wrongly
 * @param authorizationRequired whether the customer would be asked for a one-time code. It answers
 *                              the rules that are about this customer's own limits, and it does not
 *                              answer whether the payment would be reviewed for fraud: what the
 *                              alert thresholds are is not something to publish to the party they
 *                              exist to catch
 */
public record PaymentQuoteDto(
        MoneyDto amount,
        MoneyDto fee,
        MoneyDto total,
        boolean authorizationRequired
) {
}
