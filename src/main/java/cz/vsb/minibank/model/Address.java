package cz.vsb.minibank.model;


public class Address {
    public String street;
    public String city;


    public Address() {}
    public Address(String street, String city) {
        this.street = street; this.city = city;
    }


    @Override public String toString() {
        return street + ", " + city;
    }
}