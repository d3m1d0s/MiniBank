package cz.vsb.minibank.domain.fee;

import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.domain.transfer.Transfer;

/**
 * Strategy for computing transfer fees from the transfer amount.
 *
 * The contract is single-currency, because the bank is. Balances, daily limits and soft
 * thresholds are all held in CZK columns, every amount a caller can submit is built by
 * {@link Money#czkPayment}, no request contract carries a currency at all, and both daily-total
 * queries filter on the literal 'CZK'. An implementation is handed a CZK amount and answers with
 * a CZK fee. Nothing here converts between currencies, and nothing may pass a foreign one
 * through - which is the point the two shipped implementations used to disagree on, one refusing
 * a foreign amount and the other preserving it.
 */
public interface FeePolicy {

    /**
     * Computes the fee for the given transfer amount.
     *
     * @param amount the amount to be moved, in CZK
     * @return the fee to charge on top of it, in CZK, never null and never negative
     * @throws DataIntegrityException when the amount is not in CZK. It is a data problem rather
     *         than a caller's mistake: every amount a caller can submit is built by
     *         {@link Money#czkPayment}, so a foreign one can only have come from stored data.
     *         Nothing reaches an implementation through a {@link Transfer} any more - the
     *         constructor refuses a foreign amount and {@link Transfer#feeAmount} is the only
     *         route from one - so this now states what an implementation requires of any caller
     *         rather than describing a path something takes today.
     */
    Money compute(Money amount);
}
