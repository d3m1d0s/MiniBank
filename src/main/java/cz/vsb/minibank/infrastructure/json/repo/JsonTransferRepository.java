package cz.vsb.minibank.infrastructure.json.repo;

import cz.vsb.minibank.domain.DispatchState;
import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.StoredValue;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.dto.JsonTransfer;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    /**
     * One page of the transfers sent from a set of accounts, newest first.
     *
     * IT SLICES IN MEMORY, and there is no version of this backend that does not: the store is one
     * JSON document read into a list, so every matching row is filtered and sorted before the page
     * can be cut out of it. What that costs is real and is accepted - this is the demo backend and
     * the one the tests run against, while SQL does the same work in the statement.
     *
     * What is NOT allowed to differ is the answer. The order is created_at descending with the id
     * descending behind it, which is the other backend's ORDER BY, and the tie-break is what makes
     * offset paging safe: without it two rows carrying the same instant may swap between two reads,
     * so one appears on both pages and one on neither. The comparator is composed and then reversed
     * as a whole, so both keys descend together; that is safe here, unlike the reversal
     * FraudController warns about, because no null rule is composed into it - a row with no
     * readable creation instant is refused below rather than placed.
     *
     * The refusals are the loader's own, applied to the two fields this method reads without
     * building a Transfer around them. A status or a creation instant that is present and
     * unreadable is refused rather than skipped, exactly as {@link #awaitsDispatch} and
     * {@code dayKeyOf} refuse theirs, and exactly as JsonMapper would refuse the row a moment
     * later: a page that silently omits a payment is a history the customer cannot reconcile.
     */
    @Override
    public List<Transfer> bySourceAccountsNewestFirst(Collection<Integer> accountIds,
                                                      Collection<TransferStatus> statuses,
                                                      int offset, int limit) {
        if (accountIds.isEmpty() || limit <= 0) {
            return List.of();
        }

        UnitOfWork uow = UowContext.current();
        Set<TransferStatus> wanted = wantedStatuses(statuses);

        return store.read(bundle -> bundle.transfers.stream()
                .filter(dto -> accountIds.contains(dto.sourceAccountId))
                .filter(dto -> wanted.contains(statusOf(dto)))
                .sorted(Comparator.comparing(JsonTransferRepository::createdAtOf)
                        .thenComparingInt((JsonTransfer dto) -> dto.id)
                        .reversed())
                .skip(Math.max(0, offset))
                .limit(limit)
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

    /**
     * How many rows the page above is taken out of.
     *
     * Counted off the DTOs, with the page query's own two predicates and no third, so the number
     * the screen prints beside a list and the rows in that list describe one set. No Transfer is
     * built and no identity map is touched: this is a count, and substituting instances this
     * transaction holds would count nothing differently.
     */
    @Override
    public int countBySourceAccounts(Collection<Integer> accountIds,
                                     Collection<TransferStatus> statuses) {
        if (accountIds.isEmpty()) {
            return 0;
        }

        Set<TransferStatus> wanted = wantedStatuses(statuses);

        return store.read(bundle -> (int) bundle.transfers.stream()
                .filter(dto -> accountIds.contains(dto.sourceAccountId))
                .filter(dto -> wanted.contains(statusOf(dto)))
                .count());
    }

    /**
     * The statuses the two methods above admit, with an empty request read as every status.
     *
     * An EnumSet rather than the caller's collection: this is a membership test run once per
     * stored row, and it is also what makes an empty request cheap to express as "all of them"
     * rather than as a second code path with no predicate in it.
     */
    private static Set<TransferStatus> wantedStatuses(Collection<TransferStatus> statuses) {
        return statuses.isEmpty()
                ? EnumSet.allOf(TransferStatus.class)
                : EnumSet.copyOf(statuses);
    }

    /** The status a stored row names, refusing a row whose status the loader would refuse. */
    private static TransferStatus statusOf(JsonTransfer dto) {
        return StoredValue.requiredEnum(TransferStatus.class, dto.status, "status", "transfer", dto.id);
    }

    /** The creation instant a stored row names, refusing a row the loader would refuse. */
    private static Instant createdAtOf(JsonTransfer dto) {
        return StoredValue.requiredInstant(dto.createdAt, "creation instant", "transfer", dto.id);
    }

    /**
     * Every payment that has left this bank and that no gateway has been handed yet.
     *
     * Sorted by id although this store is a list that is appended to in id order, so in practice
     * it already answers that way. In practice is not the promise the interface makes, and the one
     * writer that does not append - {@link #save}, which replaces a row where it stands - is one
     * reordering away from making the accident untrue. On the other backend the order is the
     * statement's, and a sweep must retry the same payments in the same order on both.
     *
     * The whole document is under one lock while this runs, which is the same hold every read here
     * takes; inside a unit of work on this thread it is reentrant and costs nothing.
     */
    @Override
    public List<Transfer> awaitingDispatch() {
        UnitOfWork uow = UowContext.current();
        return store.read(bundle -> bundle.transfers.stream()
                .filter(JsonTransferRepository::awaitsDispatch)
                .sorted(Comparator.comparingInt((JsonTransfer dto) -> dto.id))
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

    /**
     * Whether a stored row still owes the network a dispatch, refusing a row whose answer this
     * application would refuse to load.
     *
     * The refusal is the point, and it is the same rule {@link #amountOf} and {@link #dayKeyOf}
     * apply to the fields they read: a name that is present and cannot be read is not skipped.
     * Skipping is the cheap answer here and the dangerous one - the row drops silently out of the
     * only query that will ever hand this payment to the network, so the money has left the
     * customer's account and nothing is left that knows anybody owes it. The loader refuses such a
     * row too, so this refuses exactly what it refuses and nothing more.
     *
     * Only this backend can reach it. On SQL transfers_dispatch_state_known makes a name outside
     * the enum unwritable.
     */
    private static boolean awaitsDispatch(JsonTransfer dto) {
        if (dto.dispatchState == null) {
            return false;
        }
        return StoredValue.requiredEnum(DispatchState.class, dto.dispatchState,
                "dispatch state", "transfer", dto.id) == DispatchState.PENDING;
    }

    @Override
    public Money sentTotalLeavingCustomerBetween(Collection<Integer> accountIds,
                                                 Collection<String> ownIbans,
                                                 Instant fromInclusive, Instant toExclusive) {
        // Summed off the DTOs. bySourceAccount would build a Transfer and two LazyRef closures
        // per row to read one number off each, and would substitute identity-map instances
        // whose in-memory status can already differ from the stored one. Each row is wrapped in
        // Money before it is added, so the total rounds exactly as the amounts themselves do -
        // summing the stored values first and rounding once would not.
        //
        // A SENT row this application would refuse to load is refused here too, rather than
        // skipped or summed as it stands. Skipping answers a daily total that is quietly short by
        // one payment, which is the shape of defect this ceiling exists to prevent; summing a row
        // the loader rejects is the same defect wearing the other sign, because a negative stored
        // amount does not merely fail to add, it subtracts, and buys headroom under the ceiling
        // for the next real payment. The rule is one rule and it is applied once, in amountOf and
        // dayKeyOf below, over exactly the rows Transfer's constructor and JsonMapper refuse and
        // no others - a sum that refused more than the loader does would be a second opinion about
        // what a valid row is, which is how the two backends drift apart.
        //
        // Only this backend can reach any of it. On SQL the CHECK constraints on amount and
        // currency make such a row unrepresentable and the sum runs in the database.
        //
        // The account test is a membership test over the ids the caller passed and the ids are not
        // copied into a Set, for the reason sentTotalToIbanBetween gives: a customer's handful of
        // accounts, once per stored row, under a lock already held.
        //
        // The destination test is the exclusion, and it is what a per-account ceiling could not
        // express: a payment that lands on another account of the same customer has left this
        // account without leaving the customer, so counting it would let the day's allowance be
        // spent by moving money in place. A row whose snapshot is missing matches no exclusion and
        // is therefore counted - the column is NOT NULL on the other backend only, and a row this
        // bank cannot say the destination of is not one it may call an internal move.
        //
        // store.read holds the store lock, which a JsonUnitOfWork on this thread already holds,
        // so the re-acquisition is reentrant and costs nothing.
        if (accountIds.isEmpty()) {
            return Money.czk(0.0);
        }

        Set<String> excluded = normalizedIbans(ownIbans);

        return store.read(bundle -> {
            Money total = Money.czk(0.0);
            for (JsonTransfer dto : bundle.transfers) {
                if (!accountIds.contains(dto.sourceAccountId)) continue;
                if (!TransferStatus.SENT.name().equals(dto.status)) continue;
                String destination = IBAN.normalize(dto.targetIbanSnapshot);
                if (destination != null && excluded.contains(destination)) continue;
                Instant countedOn = dayKeyOf(dto);
                if (countedOn == null) continue;
                if (countedOn.isBefore(fromInclusive) || !countedOn.isBefore(toExclusive)) continue;
                total = total.plus(amountOf(dto));
            }
            return total;
        });
    }

    /**
     * The destinations that do not count as leaving, normalized once rather than per stored row.
     *
     * A Set rather than the caller's collection, unlike the account ids beside it: this is
     * normalized, so it has to be built anyway, and building it as a list would mean a scan per
     * row over values that are already in hand. A null survives {@code IBAN.normalize} as a null
     * and is dropped, which keeps this collection saying exactly what the other backend's bound
     * array says.
     */
    private static Set<String> normalizedIbans(Collection<String> ibans) {
        return ibans.stream()
                .map(IBAN::normalize)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * The same total, narrowed to one destination and totalled over several accounts at once.
     * See the interface for why it is its own method rather than a parameter on the one above,
     * and why its scope is the customer's accounts while the day total above stays on one.
     *
     * The account test is a membership test over the ids the caller passed, so a row belonging
     * to none of them is left out exactly as a row belonging to another account used to be. The
     * ids arrive as a Collection and are not copied into a Set: this runs under the store lock
     * with a caller's own handful of accounts, and a hash set per call would cost more than the
     * scan it saves.
     *
     * Both sides of the destination comparison are normalized, and the stored one is allowed to
     * be null. Neither is paranoia: JsonTransfer.targetIbanSnapshot is a bare field with no
     * constraint behind it - the NOT NULL exists in db/init/schema.sql and nowhere else - and
     * Transfer's constructor takes the snapshot as a plain String, so a denormalized one is
     * reachable through the public domain API and CreditLegTest pins that it must still resolve.
     */
    @Override
    public Money sentTotalToIbanBetween(Collection<Integer> accountIds, String targetIban,
                                        Instant fromInclusive, Instant toExclusive) {
        String wanted = IBAN.normalize(targetIban);

        return store.read(bundle -> {
            Money total = Money.czk(0.0);
            for (JsonTransfer dto : bundle.transfers) {
                if (!accountIds.contains(dto.sourceAccountId)) continue;
                if (!TransferStatus.SENT.name().equals(dto.status)) continue;
                if (!Objects.equals(wanted, IBAN.normalize(dto.targetIbanSnapshot))) continue;
                Instant countedOn = dayKeyOf(dto);
                if (countedOn == null) continue;
                if (countedOn.isBefore(fromInclusive) || !countedOn.isBefore(toExclusive)) continue;
                total = total.plus(amountOf(dto));
            }
            return total;
        });
    }

    /**
     * The money a row being counted towards a total carries, refusing a row whose money this
     * application would refuse to load.
     *
     * Three ways a stored row fails that, and only the first of them used to arrive here.
     *
     * Absent is a value the amount field can hold at all only because it is a
     * {@link java.math.BigDecimal} rather than a primitive. That is the point of the type - a
     * primitive answered 0.00 for a missing amount and no total ever noticed - but it means the
     * reading side has to say what absent means, and here it means the store is corrupt.
     *
     * Non-positive was added to the total exactly as stored. {@link Money} fixes a scale and a
     * currency and promises nothing about sign, deliberately, since it also has to express a
     * balance going the other way; the rule that a transfer moves a strictly positive amount is
     * Transfer's constructor's, and this sum is the one place a stored amount is read without a
     * Transfer being built around it, so the rule was not reaching it. The row is therefore one
     * this very repository will not hand out of {@link #byId}, counted here with a minus sign.
     *
     * A currency that is not CZK was skipped, absent and foreign alike, since one exact-match
     * test caught both, and the skip was the whole of the defence. Both other readers of the
     * field refuse such a row - {@link Money}'s constructor a code that is not canonical,
     * Transfer's a well-formed foreign one - so a total that silently omits it is a total nobody
     * can reconcile against the statement it was computed from.
     */
    private static Money amountOf(JsonTransfer dto) {
        if (dto.amount == null) {
            throw new DataIntegrityException("Stored transfer " + dto.id + " has no amount");
        }
        if (dto.currency == null) {
            throw new DataIntegrityException("Stored transfer " + dto.id + " has no currency");
        }
        if (!"CZK".equals(dto.currency)) {
            throw new DataIntegrityException("Stored transfer " + dto.id
                    + " has an unusable currency: " + dto.currency);
        }
        Money amount = Money.czk(dto.amount);
        if (!amount.isPositive()) {
            throw new DataIntegrityException("Stored transfer " + dto.id
                    + " has an amount that is not greater than zero: " + amount);
        }
        return amount;
    }

    /**
     * The instant this row counts against: when it settled, falling back to when it was created.
     *
     * The same rule SqlTransferRepository writes as COALESCE(settled_at, created_at), stated
     * once per backend so the daily limit cannot mean different things depending on where the
     * data lives. The fallback is what makes the migration change no historical total: a row
     * written before settledAt existed keeps counting under its creation day, exactly as it did.
     * A row carrying neither timestamp counts toward no day at all, which is the NULL that fails
     * both range comparisons on the other backend, and it stays that way here.
     *
     * Absent and unreadable part company, and that distinction is the whole of this. An absent
     * settlement instant is what every transfer that has not settled carries: a fact this store
     * is meant to hold, and the reason the fallback exists. A timestamp that is present and will
     * not parse is a row no loader would accept, and there is no day it can honestly be filed
     * under - so it is refused rather than dropped, and refused whether or not the window would
     * have contained it, because the window is exactly what an unreadable timestamp makes
     * unanswerable.
     */
    private static Instant dayKeyOf(JsonTransfer dto) {
        Instant settledAt =
                StoredValue.presentInstantOrNull(dto.settledAt, "settlement instant", "transfer", dto.id);
        return settledAt != null
                ? settledAt
                : StoredValue.presentInstantOrNull(dto.createdAt, "creation instant", "transfer", dto.id);
    }
}
