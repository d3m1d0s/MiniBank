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


public class JsonDataStore {
    private final ObjectMapper om;
    private final File file;


    public static class Bundle {
        public List<JsonCustomer> customers = new ArrayList<>();
        public List<JsonAccount> accounts = new ArrayList<>();
        public List<JsonTransfer> transfers = new ArrayList<>();
        public List<JsonFraudAlert> fraudAlerts = new ArrayList<>();
    }


    private Bundle cache = new Bundle();


    public JsonDataStore(String path) {
        this.file = new File(path);
        this.om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        File dir = this.file.getParentFile(); if (dir != null) dir.mkdirs();
    }


    public synchronized Bundle data() { return cache; }


    public synchronized void save() throws Exception { om.writeValue(file, cache); }


    public synchronized void load() throws Exception {
        if (!file.exists() || Files.size(file.toPath()) == 0) { cache = new Bundle(); return; }
        cache = om.readValue(file, Bundle.class);
        if (cache.customers == null) cache.customers = new ArrayList<>();
        if (cache.accounts == null) cache.accounts = new ArrayList<>();
        if (cache.transfers == null) cache.transfers = new ArrayList<>();
        if (cache.fraudAlerts == null) cache.fraudAlerts = new ArrayList<>();
    }
}