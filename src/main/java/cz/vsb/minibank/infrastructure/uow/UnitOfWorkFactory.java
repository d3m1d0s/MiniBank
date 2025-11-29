package cz.vsb.minibank.infrastructure.uow;

public interface UnitOfWorkFactory {
    UnitOfWork begin(); // opens a new Unit of Work (in SQL this maps to a transaction/Connection)
}
