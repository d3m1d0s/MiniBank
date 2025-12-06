package cz.vsb.minibank.infrastructure.json.dto;

/**
 * JSON representation of a beneficiary entry.
 */
public class JsonBeneficiary {
    public int id;
    public String name;
    public String iban;
    public boolean trusted;
}
