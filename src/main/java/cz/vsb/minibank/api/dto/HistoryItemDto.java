package cz.vsb.minibank.api.dto;

/**
 * Single item in the payment history list.
 *
 * The account the money left stands before the account it went to, because a record's field order
 * is the key order of its JSON and a reader of the wire should be able to read the route in the
 * direction it happened. {@code fromIban} is the name {@link TransferInfoDto} already gives the
 * same fact; a second name for it would be one more thing the two desks could come to disagree on.
 *
 * Neither IBAN is ever null. Both lists that build this record resolve the source account from
 * rows they have already loaded, and both raise rather than pass a gap on: a history row that
 * cannot say which account it left is evidence with a hole in it, and the screens print it without
 * asking whether it is there.
 */
public record HistoryItemDto(
        int id,
        String createdAt,
        MoneyDto amount,
        String status,
        String fromIban,
        String toIban,
        String declineReason
) {
}
