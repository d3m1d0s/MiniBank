package cz.vsb.minibank.infrastructure.json.repo;

import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.dto.JsonTransfer;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JSON-backed implementation of {@link TransferRepository}.
 * Uses {@link JsonDataStore} as the persistence backend and participates
 * in the UnitOfWork / Identity Map mechanism when a UnitOfWork is active.
 */
public class JsonTransferRepository implements TransferRepository {
    private final JsonDataStore store;

    public JsonTransferRepository(JsonDataStore store) {
        this.store = store;
    }

    @Override
    public int nextId() {
        return store.nextTransferId();
    }

    @Override
    public void add(Transfer t) {
        UnitOfWork uow = UowContext.current();
        // A bare ArrayList.add was the dropped-element site: two concurrent adds can write
        // the same backing slot and increment size once, which is how a fraud alert ended
        // up pointing at a transfer id that is not on disk.
        Runnable mutate = () -> store.data().transfers.add(JsonMapper.toDto(t));
        if (uow != null) {
            // Runs during commit(), with the store lock already held by this thread.
            uow.registerMutation(mutate);
            uow.put(Transfer.class, t.id(), t);
        } else {
            store.mutateAndSave(mutate);
        }
    }

    @Override
    public void save(Transfer t) {
        UnitOfWork uow = UowContext.current();
        Runnable mutate = () -> {
            var list = store.data().transfers;
            int idx = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == t.id()) {
                    idx = i;
                    break;
                }
            }
            JsonTransfer dto = JsonMapper.toDto(t);
            if (idx >= 0) {
                list.set(idx, dto);
            } else {
                list.add(dto);
            }
        };
        if (uow != null) {
            uow.registerMutation(mutate);
            uow.put(Transfer.class, t.id(), t);
        } else {
            // The index scan inside mutate calls size() and get(i) separately; a concurrent
            // add leaving a null hole makes get(i).id throw.
            store.mutateAndSave(mutate);
        }
    }

    @Override
    public Optional<Transfer> byId(int id) {
        UnitOfWork uow = UowContext.current();
        if (uow != null) {
            Transfer cached = uow.get(Transfer.class, id);
            if (cached != null) {
                return Optional.of(cached);
            }
        }
        // This is the read that returned empty for a just-committed row and surfaced as
        // HTTP 500 "Transfer not found".
        return store.read(bundle -> {
            var found = bundle.transfers.stream().filter(x -> x.id == id).findFirst();
            if (found.isEmpty()) {
                return Optional.<Transfer>empty();
            }
            Transfer d = JsonMapper.toDomain(found.get(), store);
            if (uow != null) {
                uow.put(Transfer.class, d.id(), d);
            }
            return Optional.of(d);
        });
    }

    @Override
    public List<Transfer> bySourceAccount(int accountId) {
        UnitOfWork uow = UowContext.current();
        return store.read(bundle -> bundle.transfers.stream()
                .filter(t -> t.sourceAccountId == accountId)
                .map(dto -> {
                    if (uow != null) {
                        Transfer cached = uow.get(Transfer.class, dto.id);
                        if (cached != null) {
                            return cached;
                        }
                    }
                    Transfer d = JsonMapper.toDomain(dto, store);
                    if (uow != null) {
                        uow.put(Transfer.class, d.id(), d);
                    }
                    return d;
                })
                .collect(Collectors.toList()));
    }

    @Override
    public Money sentTotalBetween(int accountId, Instant fromInclusive, Instant toExclusive) {
        // Summed off the DTOs. bySourceAccount would build a Transfer and two LazyRef closures
        // per row to read one number off each, and would substitute identity-map instances
        // whose in-memory status can already differ from the stored one. Each row is wrapped in
        // Money before it is added, so the total rounds exactly as the amounts themselves do -
        // adding the raw doubles first and rounding once would not.
        //
        // store.read holds the store lock, which a JsonUnitOfWork on this thread already holds,
        // so the re-acquisition is reentrant and costs nothing.
        return store.read(bundle -> {
            Money total = Money.czk(0.0);
            for (JsonTransfer dto : bundle.transfers) {
                if (dto.sourceAccountId != accountId) continue;
                if (!TransferStatus.SENT.name().equals(dto.status)) continue;
                if (!"CZK".equals(dto.currency)) continue;
                Instant countedOn = dayKeyOf(dto);
                if (countedOn == null) continue;
                if (countedOn.isBefore(fromInclusive) || !countedOn.isBefore(toExclusive)) continue;
                total = total.plus(Money.czk(dto.amount));
            }
            return total;
        });
    }

    /**
     * The same total, narrowed to one destination. See the interface for why it is its own
     * method and not a parameter on the one above.
     *
     * Both sides of the destination comparison are normalized, and the stored one is allowed to
     * be null. Neither is paranoia: JsonTransfer.targetIbanSnapshot is a bare field with no
     * constraint behind it - the NOT NULL exists in db/init/schema.sql and nowhere else - and
     * Transfer's constructor takes the snapshot as a plain String, so a denormalized one is
     * reachable through the public domain API and CreditLegTest pins that it must still resolve.
     */
    @Override
    public Money sentTotalToIbanBetween(int accountId, String targetIban,
                                        Instant fromInclusive, Instant toExclusive) {
        String wanted = IBAN.normalize(targetIban);

        return store.read(bundle -> {
            Money total = Money.czk(0.0);
            for (JsonTransfer dto : bundle.transfers) {
                if (dto.sourceAccountId != accountId) continue;
                if (!TransferStatus.SENT.name().equals(dto.status)) continue;
                if (!"CZK".equals(dto.currency)) continue;
                if (!Objects.equals(wanted, IBAN.normalize(dto.targetIbanSnapshot))) continue;
                Instant countedOn = dayKeyOf(dto);
                if (countedOn == null) continue;
                if (countedOn.isBefore(fromInclusive) || !countedOn.isBefore(toExclusive)) continue;
                total = total.plus(Money.czk(dto.amount));
            }
            return total;
        });
    }

    /**
     * The instant this row counts against: when it settled, falling back to when it was created.
     *
     * The same rule SqlTransferRepository writes as COALESCE(settled_at, created_at), stated
     * once per backend so the daily limit cannot mean different things depending on where the
     * data lives. The fallback is what makes the migration change no historical total: a row
     * written before settledAt existed keeps counting under its creation day, exactly as it did.
     * A row with neither parseable counts toward no day at all.
     */
    private static Instant dayKeyOf(JsonTransfer dto) {
        Instant settledAt = parseInstantOrNull(dto.settledAt);
        return settledAt != null ? settledAt : parseInstantOrNull(dto.createdAt);
    }

    /**
     * A row whose timestamp is missing or unreadable counts toward no day at all.
     *
     * Parsed here rather than by going through JsonMapper, because Transfer.hydrateForLoad only
     * assigns createdAt when it is non-null: a row the mapper cannot parse keeps the moment it
     * was constructed, which would put it in today's total on every call, forever.
     */
    private static Instant parseInstantOrNull(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
