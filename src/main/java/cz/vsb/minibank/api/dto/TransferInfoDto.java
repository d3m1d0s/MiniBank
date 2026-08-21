package cz.vsb.minibank.api.dto;

/**
 * High-level transfer overview used in list or summary views.
 *
 * {@code toIbanInBank} stands beside the account it is about, and it is the same derived fact the
 * history rows beneath it carry, stated by {@link HistoryItemDto#isToIbanInBank}. Whether the money
 * left the bank is the first thing that decides what can still be done about a payment, so the desk
 * should not have to read it off the row below the one it is looking at.
 *
 * {@code dispatchState} stands beside it and not instead of it, exactly as it does on
 * {@link TransferDetailsDto}: the boolean says which side of this bank's edge the money was going,
 * this says how far it has got on the way out, and neither can be derived from the other. It is
 * here because this is the alerted payment, the one an analyst is deciding about, and the decision
 * turns on how far the money has travelled: PENDING is settled and queued for the network,
 * DISPATCHED is settled and handed over, and a payment already handed over is a case file rather
 * than something that can still be stopped. Null covers three situations at once and is not the
 * opposite answer, which is set out in {@link cz.vsb.minibank.domain.DispatchState}.
 */
public record TransferInfoDto(
        int id,
        String code,
        String status,
        String fromIban,
        MoneyDto fromBalance,
        String toIban,
        boolean toIbanInBank,
        String dispatchState,
        MoneyDto amount,
        MoneyDto feeAmount,
        String createdAt,
        String authMethod
) {
}
