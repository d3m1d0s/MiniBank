package cz.vsb.minibank.infrastructure.json.repo;

import cz.vsb.minibank.domain.fraud.FraudAlert;
import cz.vsb.minibank.domain.fraud.FraudAlertNote;
import cz.vsb.minibank.domain.fraud.FraudAlertState;
import cz.vsb.minibank.domain.transfer.TransferStatus;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.StoredValue;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.dto.JsonFraudAlert;
import cz.vsb.minibank.infrastructure.json.dto.JsonFraudAlertNote;
import cz.vsb.minibank.infrastructure.json.dto.JsonTransfer;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JSON-backed implementation of {@link FraudAlertRepository}.
 * Uses {@link JsonDataStore} as the persistence backend and participates
 * in the UnitOfWork / Identity Map mechanism when a UnitOfWork is active.
 */
public class JsonFraudAlertRepository implements FraudAlertRepository {

    private final JsonDataStore store;

    public JsonFraudAlertRepository(JsonDataStore store) {
        this.store = store;
    }

    @Override
    public int nextId() {
        return store.nextFraudAlertId();
    }

    @Override
    public void add(FraudAlert a) {
        UnitOfWork uow = UowContext.current();
        // The other bare ArrayList.add. Paired with the transfer add, this is the
        // orphan-alert mechanism.
        Runnable mutate = () -> store.data().fraudAlerts.add(JsonMapper.toDto(a));
        if (uow != null) {
            uow.registerMutation(mutate);
            uow.put(FraudAlert.class, a.id(), a);
        } else {
            store.mutateAndSave(mutate);
        }
    }

    @Override
    public void save(FraudAlert a) {
        UnitOfWork uow = UowContext.current();
        Runnable mutate = () -> {
            var list = store.data().fraudAlerts;
            int idx = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == a.id()) {
                    idx = i;
                    break;
                }
            }
            JsonFraudAlert dto = JsonMapper.toDto(a);
            if (idx >= 0) {
                list.set(idx, dto);
            } else {
                list.add(dto);
            }
        };
        if (uow != null) {
            uow.registerMutation(mutate);
            uow.put(FraudAlert.class, a.id(), a);
        } else {
            store.mutateAndSave(mutate);
        }
    }

    @Override
    public Optional<FraudAlert> byId(int id) {
        UnitOfWork uow = UowContext.current();
        if (uow != null) {
            FraudAlert cached = uow.get(FraudAlert.class, id);
            if (cached != null) return Optional.of(cached);
        }
        return store.read(bundle -> {
            var f = bundle.fraudAlerts.stream().filter(x -> x.id == id).findFirst();
            if (f.isEmpty()) return Optional.<FraudAlert>empty();
            FraudAlert d = JsonMapper.toDomain(f.get());
            if (uow != null) uow.put(FraudAlert.class, d.id(), d);
            return Optional.of(d);
        });
    }

    @Override
    public Optional<FraudAlert> byTransferId(int transferId) {
        UnitOfWork uow = UowContext.current();
        // Entry point for all three fraud use cases; iterates the list an interleaved
        // add mutates.
        return store.read(bundle -> {
            // Lowest id, the row the SQL twin's ORDER BY id ASC LIMIT 1 returns. Ambiguity is
            // resolved rather than refused as an ambiguous IBAN is, because nothing declares
            // this column unique.
            var f = bundle.fraudAlerts.stream()
                    .filter(x -> x.transferId == transferId)
                    .min(Comparator.comparingInt((JsonFraudAlert x) -> x.id));
            if (f.isEmpty()) return Optional.<FraudAlert>empty();
            JsonFraudAlert row = f.get();
            // Probed after the scan rather than before it as in byId, because the row is what
            // supplies the id. Without it a decided alert was displaced by a fresh copy of its
            // own stored row, and since commit drains domain events by walking the identity
            // map, the verdict was written to the store and never announced.
            if (uow != null) {
                FraudAlert cached = uow.get(FraudAlert.class, row.id);
                if (cached != null) return Optional.of(cached);
            }
            FraudAlert d = JsonMapper.toDomain(row);
            if (uow != null) uow.put(FraudAlert.class, d.id(), d);
            return Optional.of(d);
        });
    }

    @Override
    public List<FraudAlert> all() {
        UnitOfWork uow = UowContext.current();
        return store.read(bundle -> bundle.fraudAlerts.stream()
                .map(dto -> {
                    if (uow != null) {
                        FraudAlert cached = uow.get(FraudAlert.class, dto.id);
                        if (cached != null) return cached;
                    }
                    FraudAlert d = JsonMapper.toDomain(dto);
                    if (uow != null) uow.put(FraudAlert.class, d.id(), d);
                    return d;
                })
                .collect(Collectors.toList()));
    }

    // -------------------------------------------------------------------------
    // The notes journal
    // -------------------------------------------------------------------------

    /**
     * Appends one entry to the store's journal list. Nothing here replaces or removes one.
     *
     * A bare append, like the two adds above, and for once that is the whole point rather than a
     * concession: an append can lose nothing, however stale the caller's copy of the alert is.
     *
     * It joins the ambient unit of work when there is one, so a note is written by the same commit
     * that writes the decision it was taken with, and a decision that rolls back takes its note
     * with it.
     */
    @Override
    public void appendNote(FraudAlertNote note) {
        java.util.Objects.requireNonNull(note, "note");

        JsonFraudAlertNote row = new JsonFraudAlertNote();
        row.alertId = note.alertId();
        row.author = note.author();
        row.writtenAt = note.writtenAt().toString();
        row.text = note.text();

        UnitOfWork uow = UowContext.current();
        Runnable mutate = () -> store.data().fraudAlertNotes.add(row);
        if (uow != null) {
            uow.registerMutation(mutate);
        } else {
            store.mutateAndSave(mutate);
        }
    }

    /**
     * One alert's journal, oldest first.
     *
     * The list is appended to and never reordered, so its own order is already the order the notes
     * were written in. The sort by instant is what makes this answer the same thing the SQL twin's
     * ORDER BY does, and it is stable, so two notes sharing an instant keep the order they were
     * appended in rather than swapping between two reads of one screen.
     *
     * No identity map. A note is a value in a list, not an aggregate with a life of its own; there
     * is nothing here that a later write in the same transaction could make stale.
     */
    @Override
    public List<FraudAlertNote> notesOf(int alertId) {
        return store.read(bundle -> {
            List<FraudAlertNote> journal = new ArrayList<>();
            for (JsonFraudAlertNote row : bundle.fraudAlertNotes) {
                if (row.alertId != alertId) continue;
                journal.add(new FraudAlertNote(
                        row.alertId,
                        row.author,
                        StoredValue.requiredInstant(
                                row.writtenAt, "writing instant", "fraud alert note", row.alertId),
                        row.text));
            }
            journal.sort(Comparator.comparing(FraudAlertNote::writtenAt));
            return journal;
        });
    }

    // -------------------------------------------------------------------------
    // The analyst queue
    // -------------------------------------------------------------------------

    /**
     * One page of the queue, joined, filtered, ordered and sliced in memory.
     *
     * It slices in memory, and it says so rather than pretending otherwise: this store is one JSON
     * document that is read whole under one lock, so there is no offset a page could be pushed
     * down to. What matters is that it answers the same contract the SQL twin does - the same
     * inclusive bounds, the same containment test on the assignee, the same newest-first order
     * with the id tie break, and the same silence about an alert whose payment is gone.
     *
     * What it does not do is build a {@code FraudAlert} out of every stored row to throw most of
     * them away. The filter reads the stored records, and only the rows that survive the slice are
     * mapped, so the identity map is left holding the page rather than the store. The payment is
     * never mapped at all: two values are read off its record, which is this backend's form of the
     * join that killed the per-alert query on the other one.
     */
    @Override
    public List<QueueRow> queuePage(QueueFilter filter, int offset, int limit) {
        UnitOfWork uow = UowContext.current();

        return store.read(bundle -> {
            List<Match> matches = matching(bundle, filter);
            matches.sort(NEWEST_FIRST);

            int from = Math.min(Math.max(offset, 0), matches.size());
            int to = Math.min(from + Math.max(limit, 0), matches.size());

            List<QueueRow> rows = new ArrayList<>();
            for (Match m : matches.subList(from, to)) {
                FraudAlert alert = (uow != null) ? uow.get(FraudAlert.class, m.alert.id) : null;
                if (alert == null) {
                    alert = JsonMapper.toDomain(m.alert);
                    if (uow != null) uow.put(FraudAlert.class, alert.id(), alert);
                }

                TransferStatus status = StoredValue.requiredEnum(
                        TransferStatus.class, m.transfer.status, "status", "transfer",
                        m.transfer.id);

                rows.add(new QueueRow(alert, m.transfer.id, status, amountOf(m.transfer)));
            }
            return rows;
        });
    }

    @Override
    public int queueTotal(QueueFilter filter) {
        return store.read(bundle -> matching(bundle, filter).size());
    }

    /**
     * Every alert by state, over the whole store and before any filter.
     *
     * Counted over the alerts alone, with no join to the payments, which is what the SQL twin does
     * and what counting the whole list gave before. On this backend that difference is reachable:
     * an alert can outlive the payment it was raised on, and it is still work somebody has to
     * decide.
     */
    @Override
    public Map<FraudAlertState, Integer> countByState() {
        return store.read(bundle -> {
            Map<FraudAlertState, Integer> counts = new EnumMap<>(FraudAlertState.class);
            for (JsonFraudAlert a : bundle.fraudAlerts) {
                FraudAlertState st = StoredValue.requiredEnum(
                        FraudAlertState.class, a.state, "state", "fraud alert", a.id);
                counts.merge(st, 1, Integer::sum);
            }
            return counts;
        });
    }

    /** A stored alert and the stored payment behind it, paired once so nothing is looked up twice. */
    private record Match(JsonFraudAlert alert, JsonTransfer transfer, Instant createdAt) {
    }

    /**
     * Newest first, with the id as the tie break, so that a slice taken now and a slice taken after
     * the next request describe the same list.
     */
    private static final Comparator<Match> NEWEST_FIRST =
            Comparator.comparing(Match::createdAt).reversed()
                    .thenComparing(Comparator.comparingInt((Match m) -> m.alert.id).reversed());

    /**
     * The stored alerts the filter keeps, each paired with the payment behind it.
     *
     * An alert whose payment is missing is dropped here rather than later, which is the inner join
     * the SQL twin gets from the database.
     */
    private static List<Match> matching(JsonDataStore.Bundle bundle, QueueFilter filter) {
        Map<Integer, JsonTransfer> byId = new HashMap<>();
        for (JsonTransfer t : bundle.transfers) {
            byId.put(t.id, t);
        }

        String assignee = (filter.assigneeContains() != null && !filter.assigneeContains().isBlank())
                ? filter.assigneeContains().trim().toLowerCase(Locale.ROOT)
                : null;

        List<Match> matches = new ArrayList<>();

        for (JsonFraudAlert a : bundle.fraudAlerts) {
            if (filter.state() != null && !filter.state().name().equals(a.state)) {
                continue;
            }

            // Read exactly as the loader reads it, so a row this refuses is a row the queue could
            // not have shown anyway.
            Instant createdAt = StoredValue.requiredInstant(
                    a.createdAt, "creation instant", "fraud alert", a.id);

            if (filter.createdFrom() != null && createdAt.isBefore(filter.createdFrom())) continue;
            if (filter.createdTo() != null && createdAt.isAfter(filter.createdTo())) continue;

            if (assignee != null
                    && (a.assignee == null || !a.assignee.toLowerCase(Locale.ROOT).contains(assignee))) {
                continue;
            }

            JsonTransfer t = byId.get(a.transferId);
            if (t == null) continue;

            if (!filter.excludedTransferStatuses().isEmpty()) {
                TransferStatus status = StoredValue.requiredEnum(
                        TransferStatus.class, t.status, "status", "transfer", t.id);
                if (filter.excludedTransferStatuses().contains(status)) continue;
            }

            if (filter.minAmount() != null || filter.maxAmount() != null) {
                java.math.BigDecimal amount = amountOf(t).amount();
                if (filter.minAmount() != null && amount.compareTo(filter.minAmount()) < 0) continue;
                if (filter.maxAmount() != null && amount.compareTo(filter.maxAmount()) > 0) continue;
            }

            matches.add(new Match(a, t, createdAt));
        }

        return matches;
    }

    /**
     * The stored amount as the domain holds it, refusing a record that names neither.
     *
     * Rebuilt from the stored currency rather than forced to crowns, for the reason
     * {@code JsonMapper} gives where it does the same: a record written in anything else has to
     * arrive as what it claims to be.
     */
    private static Money amountOf(JsonTransfer t) {
        if (t.amount == null) {
            throw new DataIntegrityException("Stored transfer " + t.id + " has no amount");
        }
        if (t.currency == null) {
            throw new DataIntegrityException("Stored transfer " + t.id + " has no currency");
        }
        return Money.of(t.currency, t.amount);
    }
}
