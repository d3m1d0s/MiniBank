package cz.vsb.minibank.domain;

import cz.vsb.minibank.domain.value.Money;

/**
 * Service that evaluates transfer risk and produces a risk decision.
 */
public interface RiskService {

    /**
     * Evaluates risk for a potential transfer.
     *
     * @param beneficiaryTrusted whether the beneficiary is trusted
     * @param amount             transfer amount
     * @param dailyLimit         daily limit of the source account
     * @return decision describing required actions and risk level
     */
    RiskDecision evaluate(boolean beneficiaryTrusted, Money amount, Money dailyLimit);
}
