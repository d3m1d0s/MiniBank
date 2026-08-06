package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.value.Money;

/**
 * Strategy for computing transfer fees from the transfer amount.
 *
 * The contract is single-currency, because the bank is. Balances, daily limits and soft
 * thresholds are all held in CZK columns, both wire contracts carry a bare amount in crowns with
 * no currency field at all, and both daily-total queries filter on the literal 'CZK'. An
 * implementation is handed a CZK amount and answers with a CZK fee. Nothing here converts between
 * currencies, and nothing may pass a foreign one through - which is the point the two shipped
 * implementations used to disagree on, one refusing a foreign amount and the other preserving it.
 */
public interface FeePolicy {

    /**
     * Computes the fee for the given transfer amount.
     *
     * @param amount the amount to be moved, in CZK
     * @return the fee to charge on top of it, in CZK, never null and never negative
     * @throws DataIntegrityException when the amount is not in CZK. It is a data problem rather
     *         than a caller's mistake: both creation paths stamp the literal "CZK" and no wire
     *         contract carries a currency, so the only way a foreign amount reaches an
     *         implementation is a stored row written by hand.
     */
    Money compute(Money amount);
}
