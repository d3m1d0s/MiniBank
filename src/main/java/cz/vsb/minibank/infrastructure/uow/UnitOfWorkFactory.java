package cz.vsb.minibank.infrastructure.uow;

public interface UnitOfWorkFactory {
    UnitOfWork begin(); // открывает новый UoW (в SQL это будет транзакция/Connection)
}
