package cz.vsb.minibank;


import cz.vsb.minibank.repo.InMemoryDb;
import cz.vsb.minibank.storage.DataStore;
import cz.vsb.minibank.ui.Menu;


public class Main {
    public static void main(String[] args) {
        InMemoryDb db = new InMemoryDb();
        DataStore store = new DataStore("storage/data.json");
// lazy load — если файл есть, поднимем данные
        try { store.load(db); } catch (Exception ignored) {}
        new Menu(db, store).run();
    }
}