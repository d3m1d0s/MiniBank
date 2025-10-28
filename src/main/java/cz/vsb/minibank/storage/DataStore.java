package cz.vsb.minibank.storage;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import cz.vsb.minibank.model.*;
import cz.vsb.minibank.repo.InMemoryDb;


import java.io.File;
import java.nio.file.Files;
import java.util.List;


public class DataStore {
    private final ObjectMapper om;
    private final File file;


    public DataStore(String path) {
        this.file = new File(path);
        this.om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.file.getParentFile().mkdirs();
    }


    public void save(InMemoryDb db) throws Exception {
        Bundle b = new Bundle();
        b.customers = db.customers; b.accounts = db.accounts;
        b.beneficiaries = db.beneficiaries; b.transfers = db.transfers;
        om.writeValue(file, b);
        System.out.println("[OK] Data saved to " + file.getAbsolutePath());
    }


    public void load(InMemoryDb db) throws Exception {
        if (!file.exists() || Files.size(file.toPath()) == 0) return;
        Bundle b = om.readValue(file, Bundle.class);
        db.customers = b.customers != null ? b.customers : db.customers;
        db.accounts = b.accounts != null ? b.accounts : db.accounts;
        db.beneficiaries = b.beneficiaries != null ? b.beneficiaries : db.beneficiaries;
        db.transfers = b.transfers != null ? b.transfers : db.transfers;
        System.out.println("[OK] Data loaded from " + file.getAbsolutePath());
    }


    public static class Bundle {
        public List<Customer> customers;
        public List<Account> accounts;
        public List<Beneficiary> beneficiaries;
        public List<Transfer> transfers;
    }
}