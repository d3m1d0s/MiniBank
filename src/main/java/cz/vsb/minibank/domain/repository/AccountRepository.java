package cz.vsb.minibank.domain.repository;


import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.value.IBAN;


import java.util.List;
import java.util.Optional;


public interface AccountRepository {
    int nextId();
    Optional<Account> byId(int id);
    Optional<Account> byIban(IBAN iban);
    void save(Account account); // insert/update
    List<Account> byCustomerId(int customerId);
}