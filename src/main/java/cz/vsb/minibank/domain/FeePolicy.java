package cz.vsb.minibank.domain;


import cz.vsb.minibank.domain.value.Money;


public interface FeePolicy {
    Money compute(Money amount);
}