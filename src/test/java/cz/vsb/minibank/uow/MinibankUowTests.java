package cz.vsb.minibank.uow;

import cz.vsb.minibank.domain.*;
import cz.vsb.minibank.domain.repository.*;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.*;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MinibankUowTests {

    Path tempDir;
    String dataPath;
    Bootstrap infra;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("minibank-uow-");
        dataPath = tempDir.resolve("data.json").toString();
        infra = new Bootstrap(dataPath);

        // начальные данные (вне UoW — можно сразу сохранить)
        int cid = infra.customers.nextId();
        Customer c = new Customer(cid, "Test User", "test@example.com", new Address("Street 1", "City"));
        infra.customers.save(c);

        int accId = infra.accounts.nextId();
        Account a = new Account(accId, new IBAN("CZ6508000000192000145399"), Money.czk(20000), Money.czk(5000));
        infra.accounts.save(a);

        c.addAccountId(accId);
        infra.customers.save(c);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null) {
            // очищаем временную папку
            Files.walk(tempDir)
                    .sorted((p1, p2) -> p2.compareTo(p1))
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }

    @Test
    void identityMap_sameInstanceWithinUow() {
        UnitOfWork uow = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            int accId = infra.accounts.byCustomerId(infra.customers.byId(1).orElseThrow().id())
                    .get(0).id();

            Account a1 = infra.accounts.byId(accId).orElseThrow();
            Account a2 = infra.accounts.byId(accId).orElseThrow();

            assertSame(a1, a2, "В пределах одного UoW должны возвращаться один и тот же объект Account");
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback(); throw e;
        }
    }

    @Test
    void saveBeneficiary_updatesCacheAndPersistsOnCommit() {
        int customerId = infra.customers.byId(1).orElseThrow().id();

        UnitOfWork uow = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            // загружаем Customer в кэш
            Customer cached = infra.customers.byId(customerId).orElseThrow();
            assertTrue(cached.beneficiaries().isEmpty(), "Старт без получателей");

            // сохраняем нового получателя — идёт через репозиторий с UoW
            int bid = infra.customers.nextBeneficiaryId();
            Beneficiary b = new Beneficiary(bid, "Alice", new IBAN("CZ0201000000000098765432"), false);
            infra.customers.saveBeneficiary(customerId, b);

            // кэш агрегата должен быть обновлён немедленно:
            assertEquals(1, cached.beneficiaries().size(), "Кэш агрегата Customer в UoW должен отражать изменения");
            assertEquals(bid, cached.beneficiaries().get(0).id());
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback(); throw e;
        }

        // новая сессия/UoW → читаем из персистентного хранилища
        UnitOfWork uow2 = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow2)) {
            Customer reloaded = infra.customers.byId(customerId).orElseThrow();
            assertEquals(1, reloaded.beneficiaries().size(), "После commit данные должны быть сохранены в JSON");
            assertEquals("Alice", reloaded.beneficiaries().get(0).name());
            uow2.commit();
        } catch (RuntimeException e) {
            uow2.rollback(); throw e;
        }
    }

    @Test
    void byCustomerId_usesIdentityMap() {
        int customerId = infra.customers.byId(1).orElseThrow().id();

        UnitOfWork uow = infra.uowFactory.begin();
        try (UowScope __ = new UowScope(uow)) {
            // сначала byId — кладём аккаунт в кэш
            int accId = infra.accounts.byCustomerId(customerId).get(0).id();
            Account byId = infra.accounts.byId(accId).orElseThrow();

            // затем byCustomerId — должен вернуть тот же инстанс
            List<Account> list = infra.accounts.byCustomerId(customerId);
            assertFalse(list.isEmpty());
            Account fromList = list.get(0);

            assertSame(byId, fromList, "byCustomerId должен возвращать тот же инстанс из Identity Map в рамках UoW");
            uow.commit();
        } catch (RuntimeException e) {
            uow.rollback(); throw e;
        }
    }
}
