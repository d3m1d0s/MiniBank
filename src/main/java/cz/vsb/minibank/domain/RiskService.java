package cz.vsb.minibank.domain;


import cz.vsb.minibank.domain.value.Money;


public interface RiskService {
    RiskDecision evaluate(boolean beneficiaryTrusted, Money amount, Money dailyLimit);
}