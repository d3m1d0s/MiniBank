package cz.vsb.minibank.domain.repository;


import cz.vsb.minibank.domain.FraudAlert;


import java.util.List;
import java.util.Optional;


public interface FraudAlertRepository {
    int nextId();
    void add(FraudAlert a);
    void save(FraudAlert a);
    Optional<FraudAlert> byId(int id);
    Optional<FraudAlert> byTransferId(int transferId);
    List<FraudAlert> all();
}