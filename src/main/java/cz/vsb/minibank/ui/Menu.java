package cz.vsb.minibank.ui;

import cz.vsb.minibank.model.*;
import cz.vsb.minibank.repo.InMemoryDb;
import cz.vsb.minibank.storage.DataStore;

import java.util.*;

public class Menu {
    private final InMemoryDb db;
    private final DataStore store;
    private final Scanner sc = new Scanner(System.in);

    public Menu(InMemoryDb db, DataStore store) { this.db = db; this.store = store; }

    public void run() {
        while (true) {
            printMenu();
            String choice = read("Select: ");
            try {
                switch (choice) {
                    case "1" -> addCustomer();
                    case "2" -> addAccount();
                    case "3" -> addBeneficiary();
                    case "4" -> createTransfer();
                    case "5" -> authorizeTransfer();
                    case "6" -> viewTransfers();
                    case "7" -> generateStatement();
                    case "8" -> store.save(db);
                    case "9" -> store.load(db);
                    case "0" -> { System.out.println("Bye"); return; }
                    default -> System.out.println("Unknown option");
                }
            } catch (Exception e) {
                System.out.println("[ERR] " + e.getMessage());
            }
        }
    }

    private void printMenu() {
        System.out.println("\n=== MINI-BANK ===");
        System.out.println("1) Add Customer");
        System.out.println("2) Add Account");
        System.out.println("3) Add Beneficiary");
        System.out.println("4) Create Transfer");
        System.out.println("5) Authorize Transfer");
        System.out.println("6) View Transfers");
        System.out.println("7) Generate Statement");
        System.out.println("8) Save Data");
        System.out.println("9) Load Data");
        System.out.println("0) Exit");
    }

    private void addCustomer() {
        int id = askInt("Customer id (0=auto): ");
        if (id == 0) id = db.nextCustomerId();
        String name = read("Name: ");
        String email = read("Email: ");
        String street = read("Street: ");
        String city = read("City: ");
        Customer c = new Customer(id, name, email, new Address(street, city));
        db.customers.add(c);
        System.out.println("[OK] Added customer: " + c);
    }

    private void addAccount() {
        int ownerId = askInt("Owner customer id: ");
        Customer c = db.findCustomer(ownerId).orElseThrow(() -> new RuntimeException("Customer not found"));
        String iban = read("IBAN (CZ..): ");
        if (!isValidCzIban(iban)) throw new RuntimeException("Invalid IBAN format (demo check)");
        double balance = askDouble("Initial balance: ");
        double daily = askDouble("Daily limit: ");
        int id = db.nextAccountId();
        Account a = new Account(id, iban, balance, daily, ownerId);
        db.accounts.add(a);
        c.accountIds.add(id);
        System.out.println("[OK] Added account: " + a);
    }

    private void addBeneficiary() {
        int customerId = askInt("Customer id: ");
        Customer c = db.findCustomer(customerId).orElseThrow(() -> new RuntimeException("Customer not found"));
        String name = read("Beneficiary name: ");
        String iban = read("Beneficiary IBAN: ");
        if (!isValidCzIban(iban)) throw new RuntimeException("Invalid IBAN format");
        int id = db.nextBeneficiaryId();
        Beneficiary b = new Beneficiary(id, name, iban, askYesNo("Trusted (y/n): "));
        c.beneficiaries.add(b);
        db.beneficiaries.add(b);
        System.out.println("[OK] Added beneficiary: " + b);
    }

    private void createTransfer() {
        int srcId = askInt("Source account id: ");
        Account src = db.findAccount(srcId).orElseThrow(() -> new RuntimeException("Account not found"));
        String targetIban = read("Target IBAN: ");
        if (!isValidCzIban(targetIban)) throw new RuntimeException("Invalid IBAN");
        double amount = askDouble("Amount: ");
        if (amount <= 0) throw new RuntimeException("Amount must be > 0");
        if (amount > src.balance) throw new RuntimeException("Insufficient funds");

        Transfer t = new Transfer(db.nextTransferId(), srcId, targetIban, amount);
        // Простое правило: если сумма превышает дневной лимит — нужна авторизация
        if (amount > src.dailyLimit) {
            t.status = "WAITING_AUTH";
            t.authMethod = new CardPayment(amount, "**** 1234");
        } else {
            t.status = "SENT";
            src.balance -= amount + t.calculateFee();
        }
        db.transfers.add(t);
        src.transferIds.add(t.id);
        System.out.println("[OK] Transfer created: " + t);
    }

    private void authorizeTransfer() {
        int id = askInt("Transfer id to authorize: ");
        Transfer t = db.transfers.stream().filter(x -> x.id == id).findFirst()
                .orElseThrow(() -> new RuntimeException("Transfer not found"));
        if (!"WAITING_AUTH".equals(t.status)) throw new RuntimeException("Transfer not in WAITING_AUTH");
        String code = read("Enter OTP (demo '0000'): ");
        if ("0000".equals(code)) {
            Account src = db.findAccount(t.sourceAccountId).orElseThrow();
            if (t.amount > src.balance) throw new RuntimeException("Insufficient funds");
            src.balance -= t.amount + t.calculateFee();
            t.status = "SENT";
            System.out.println("[OK] Authorized & sent.");
        } else {
            t.status = "DECLINED";
            System.out.println("[WARN] Authorization failed. Transfer declined.");
        }
    }

    private void viewTransfers() {
        System.out.println("Transfers:");
        db.transfers.forEach(t -> System.out.println("  " + t));
    }

    private void generateStatement() {
        int accId = askInt("Account id: ");
        Account a = db.findAccount(accId).orElseThrow(() -> new RuntimeException("Account not found"));
        System.out.println("Statement for account " + a.iban + ":");
        double total = 0.0, fees = 0.0;
        for (Transfer t : db.transfers) {
            if (t.sourceAccountId == accId && "SENT".equals(t.status)) {
                double fee = t.calculateFee();
                System.out.println("  #" + t.id + " -> " + t.targetIban + ": " + t.amount + " CZK (fee " + fee + ")");
                total += t.amount; fees += fee;
            }
        }
        System.out.println("TOTAL: " + total + ", FEES: " + fees + ", BALANCE: " + a.balance);
    }

    // --- helpers ---
    private String read(String prompt) {
        System.out.print(prompt);
        return sc.nextLine().trim();
    }
    private int askInt(String p) { return Integer.parseInt(read(p)); }
    private double askDouble(String p) { return Double.parseDouble(read(p)); }
    private boolean askYesNo(String p) { return read(p).toLowerCase(Locale.ROOT).startsWith("y"); }

    private boolean isValidCzIban(String iban) {
        // минимальная учебная проверка для cv2: длина и префикс
        String s = iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return s.startsWith("CZ") && s.length() >= 10 && s.length() <= 34;
    }
}