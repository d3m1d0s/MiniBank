package cz.vsb.minibank.domain.repository;


import cz.vsb.minibank.domain.Transfer;


import java.util.List;
import java.util.Optional;


public interface TransferRepository {
    int nextId();
    void add(Transfer t);
    void save(Transfer t);
    Optional<Transfer> byId(int id);
    List<Transfer> bySourceAccount(int accountId);
}