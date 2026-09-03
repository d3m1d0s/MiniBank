package cz.vsb.minibank.domain.customer;

/**
 * Postal address used for customer contact information.
 */
public class Address {
    private String street;
    private String city;

    /**
     * Creates a new address with street and city.
     */
    public Address(String street, String city) {
        this.street = street;
        this.city = city;
    }

    public String street() { return street; }
    public String city() { return city; }
}
