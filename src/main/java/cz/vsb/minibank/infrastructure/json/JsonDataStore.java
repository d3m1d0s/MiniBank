package cz.vsb.minibank.infrastructure.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import cz.vsb.minibank.infrastructure.json.dto.JsonAccount;
import cz.vsb.minibank.infrastructure.json.dto.JsonCustomer;
import cz.vsb.minibank.infrastructure.json.dto.JsonFraudAlert;
import cz.vsb.minibank.infrastructure.json.dto.JsonTransfer;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

public class JsonDataStore {
    private final ObjectMapper om;
    private final File file;



    // NEW: централизованные счётчики ID, которые сериализуются в JSON
    public static class Sequences {
        public int customer = 1;
        public int account = 100;
        public int beneficiary = 10;
        public int transfer = 5001;
        public int fraudAlert = 9001;
    }

    public static class Bundle {
        public List<JsonCustomer> customers = new ArrayList<>();
        public List<JsonAccount> accounts = new ArrayList<>();
        public List<JsonTransfer> transfers = new ArrayList<>();
        public List<JsonFraudAlert> fraudAlerts = new ArrayList<>();
        // NEW: секция последовательностей
        public Sequences sequences = new Sequences();
    }

    private Bundle cache = new Bundle();

    public JsonDataStore(String path) {
        this.file = new File(path);
        this.om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        File dir = this.file.getParentFile(); if (dir != null) dir.mkdirs();
    }

    public synchronized Bundle data() { return cache; }

    public synchronized void save() throws Exception { om.writeValue(file, cache); }

    // CHANGED: инициализация sequences с автоподхватом max+1 для обратной совместимости
    public synchronized void load() throws Exception {
        if (!file.exists() || Files.size(file.toPath()) == 0) { cache = new Bundle(); return; }
        cache = om.readValue(file, Bundle.class);
        if (cache.customers == null) cache.customers = new ArrayList<>();
        if (cache.accounts == null) cache.accounts = new ArrayList<>();
        if (cache.transfers == null) cache.transfers = new ArrayList<>();
        if (cache.fraudAlerts == null) cache.fraudAlerts = new ArrayList<>();
        if (cache.sequences == null) cache.sequences = new Sequences();

        // если в старом файле нет значений sequences — вычислим безопасные next значения
        cache.sequences.customer    = Math.max(cache.sequences.customer,
                nextFromList(cache.customers,       (cz.vsb.minibank.infrastructure.json.dto.JsonCustomer  c) -> c.id, 1));
        cache.sequences.account     = Math.max(cache.sequences.account,
                nextFromList(cache.accounts,        (cz.vsb.minibank.infrastructure.json.dto.JsonAccount   a) -> a.id, 100));
        cache.sequences.transfer    = Math.max(cache.sequences.transfer,
                nextFromList(cache.transfers,       (cz.vsb.minibank.infrastructure.json.dto.JsonTransfer  t) -> t.id, 5001));
        cache.sequences.fraudAlert  = Math.max(cache.sequences.fraudAlert,
                nextFromList(cache.fraudAlerts,     (cz.vsb.minibank.infrastructure.json.dto.JsonFraudAlert f) -> f.id, 9001));
    }

    // NEW: утилиты для расчётов max+1
    private static <T> int nextFromList(List<T> list, ToIntFunction<T> getId, int defaultStart) {
        int max = defaultStart - 1;
        for (T o : list) {
            int v = getId.applyAsInt(o);
            if (v > max) max = v;
        }
        return max + 1;
    }
    private static int nextFromCustomersBeneficiaries(List<JsonCustomer> customers, int defaultStart) {
        int max = defaultStart - 1;
        for (JsonCustomer c : customers) {
            if (c.beneficiaries != null) {
                for (var b : c.beneficiaries) {
                    if (b.id > max) max = b.id;
                }
            }
        }
        return max + 1;
    }

    // NEW: безопасное сохранение (без checked exceptions)
    private synchronized void saveQuiet() {
        try { save(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // NEW: централизованные генераторы ID (persistují se hned po inkrementu)
    public synchronized int nextCustomerId()   { int id = cache.sequences.customer++;    saveQuiet(); return id; }
    public synchronized int nextAccountId()    { int id = cache.sequences.account++;     saveQuiet(); return id; }
    public synchronized int nextBeneficiaryId(){ int id = cache.sequences.beneficiary++; saveQuiet(); return id; }
    public synchronized int nextTransferId()   { int id = cache.sequences.transfer++;    saveQuiet(); return id; }
    public synchronized int nextFraudAlertId() { int id = cache.sequences.fraudAlert++;  saveQuiet(); return id; }
}
