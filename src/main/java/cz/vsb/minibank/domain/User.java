package cz.vsb.minibank.domain;

import java.util.Objects;

public class User {
    private final int id;
    private final String username;
    private final byte[] passwordHash;
    private final byte[] passwordSalt;
    private final UserRole role;
    /**
     * Associated customer (for CUSTOMER); may be null for an analyst.
     */
    private final Integer customerId;

    public User(int id,
                String username,
                byte[] passwordHash,
                byte[] passwordSalt,
                UserRole role,
                Integer customerId) {
        this.id = id;
        this.username = Objects.requireNonNull(username, "username");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.passwordSalt = Objects.requireNonNull(passwordSalt, "passwordSalt");
        this.role = Objects.requireNonNull(role, "role");
        this.customerId = customerId;
    }

    public int id() { return id; }
    public String username() { return username; }
    public byte[] passwordHash() { return passwordHash; }
    public byte[] passwordSalt() { return passwordSalt; }
    public UserRole role() { return role; }
    public Integer customerId() { return customerId; }

    public boolean isCustomer() {
        return role == UserRole.CUSTOMER && customerId != null;
    }

    public boolean hasRole(UserRole role) {
        return this.role == role;
    }
}
