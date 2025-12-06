package cz.vsb.minibank.domain;

import java.util.Objects;

/**
 * Authenticated user with credentials, role and optional linked customer.
 */
public class User {
    private final int id;
    private final String username;
    private final byte[] passwordHash;
    private final byte[] passwordSalt;
    private final UserRole role;

    /**
     * Associated customer for CUSTOMER role; may be null for an analyst or non-customer user.
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

    /**
     * Returns true if this user is a customer and has a customer identifier.
     */
    public boolean isCustomer() {
        return role == UserRole.CUSTOMER && customerId != null;
    }

    /**
     * Returns true if the user has the given role.
     */
    public boolean hasRole(UserRole role) {
        return this.role == role;
    }
}
